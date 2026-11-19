using System.Runtime.InteropServices;
using System.Security.Principal;

namespace AirBridge;

/// <summary>The format half of the shared frame channel: what the phone
/// is sending (source/sensor-raw dimensions) plus the orientation the
/// virtual-camera source must apply. Mirrors srvcam::FrameFormat.</summary>
public readonly record struct VirtualCameraFormat(
    int Width, int Height, int Fps, int Rotation, bool Mirrored)
{
    public static readonly VirtualCameraFormat Default = new(1280, 720, 30, 0, false);
}

/// <summary>
/// Writer side of the shared-memory frame channel consumed by the
/// SoundRelay virtual-camera media source DLL (which the Windows Camera
/// Frame Server hosts in its own process).
///
/// The byte layout is a protocol, defined in
/// windows-native/VirtualCamera/SharedFrameChannel.h and implemented
/// for both sides in FrameChannelCore.h — this class is a field-for-
/// field mirror of that header's writer. Change one, change the other.
///
/// The mapping is created with an explicit DACL (current user: full;
/// LOCAL SERVICE and SYSTEM: read) because the reader does not run as
/// the interactive user: Frame Server hosts the media source. With the
/// default DACL the mapping would be invisible/unreadable to it.
///
/// Concurrency (single writer = this class, single reader = the DLL):
/// pixels and slot meta first, memory barrier, slot sequence, barrier,
/// header latest-sequence. A slot's sequence is written last, so a
/// reader that sees slot.seq == header.latestSeq knows the frame is
/// complete; it re-validates after copying (see FrameChannelCore.h).
/// </summary>
public sealed class VirtualCameraChannel : IDisposable
{
    // ---- layout constants: mirror of SharedFrameChannel.h ----
    public const string MappingName = "SoundRelayVirtualCameraFrames.v1";
    private const uint Magic = 0x43565253; // "SRVC"
    private const uint ChannelVersion = 1;
    private const int HeaderBytes = 64;
    private const int SlotCount = 2;
    private const int MaxFrameWidth = 1920;
    private const int MaxFrameHeight = 1080;
    private const int MaxFrameBytes = MaxFrameWidth * MaxFrameHeight * 4;
    private const int SlotHeaderBytes = 32;
    private const int TotalBytes = HeaderBytes + SlotCount * (SlotHeaderBytes + MaxFrameBytes);

    private const int OffMagic = 0, OffVersion = 4, OffHeaderBytes = 8, OffSlotCount = 12;
    private const int OffFrameWidth = 16, OffFrameHeight = 20, OffStride = 24, OffFps = 28;
    private const int OffRotation = 32, OffMirrored = 36, OffLatestSeq = 40;
    private const int OffLatestTimestampUs = 48, OffActive = 56;

    private const int SlotOffSeq = 0, SlotOffTimestampUs = 8, SlotOffWidth = 16;
    private const int SlotOffHeight = 20, SlotOffStride = 24, SlotOffPixels = 32;

    private static int SlotOffset(int i) => HeaderBytes + i * (SlotHeaderBytes + MaxFrameBytes);

    private IntPtr _mapping = IntPtr.Zero;
    private IntPtr _view = IntPtr.Zero;

    public string? LastError { get; private set; }
    /// <summary>True when this process created the mapping (and thus
    /// initialized it). False when a mapping from an earlier, still-
    /// mapped instance was opened — its contents are left untouched.</summary>
    public bool CreatedNew { get; private set; }

    /// <summary>Create-or-open the mapping and map a writable view.
    /// Never throws; failures land in LastError.</summary>
    public bool Open()
    {
        if (_view != IntPtr.Zero) return true;
        if (!OperatingSystem.IsWindows())
        {
            LastError = "shared frame channel requires Windows";
            return false;
        }
        IntPtr sd = IntPtr.Zero;
        try
        {
            // DACL: the reader (Frame Server) does not run as this user.
            string userSid = WindowsIdentity.GetCurrent().User?.Value ?? "";
            if (userSid.Length == 0) { LastError = "cannot determine current user SID"; return false; }
            string sddl = $"D:(A;;GA;;;{userSid})(A;;GR;;;LS)(A;;GR;;;SY)";
            if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    sddl, 1 /* SDDL_REVISION_1 */, out sd, out _))
            {
                LastError = $"ConvertStringSecurityDescriptor failed: {Marshal.GetLastWin32Error()}";
                return false;
            }
            var sa = new SECURITY_ATTRIBUTES
            {
                nLength = Marshal.SizeOf<SECURITY_ATTRIBUTES>(),
                lpSecurityDescriptor = sd,
                bInheritHandle = false,
            };
            _mapping = CreateFileMappingW(new IntPtr(-1), ref sa,
                0x04 /* PAGE_READWRITE */, 0, (uint)TotalBytes, MappingName);
            if (_mapping == IntPtr.Zero)
            {
                LastError = $"CreateFileMapping failed: {Marshal.GetLastWin32Error()}";
                return false;
            }
            CreatedNew = Marshal.GetLastWin32Error() != 183 /* ERROR_ALREADY_EXISTS */;
            _view = MapViewOfFile(_mapping, 0x0002 /* FILE_MAP_WRITE */, 0, 0, (UIntPtr)TotalBytes);
            if (_view == IntPtr.Zero)
            {
                LastError = $"MapViewOfFile failed: {Marshal.GetLastWin32Error()}";
                CloseHandle(_mapping);
                _mapping = IntPtr.Zero;
                return false;
            }
            if (CreatedNew) Init();
            else if (!IsValid)
            {
                // Existed but never initialized (creator died between
                // create and init) — do not init under a possible live
                // reader; report and let the caller surface it.
                LastError = "existing frame channel is not initialized";
                Dispose();
                return false;
            }
            return true;
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
            Dispose();
            return false;
        }
        finally
        {
            if (sd != IntPtr.Zero) LocalFree(sd);
        }
    }

    public bool IsValid =>
        _view != IntPtr.Zero && Marshal.ReadInt32(_view, OffMagic) == unchecked((int)Magic)
        && Marshal.ReadInt32(_view, OffVersion) == (int)ChannelVersion;

    private void Init()
    {
        // Zero the whole mapping, then stamp the header (TotalBytes is
        // ~16.6 MB of untouched pages — the OS zero-fills on first
        // touch, so an explicit pass costs nothing extra in practice).
        var zeros = new byte[64 * 1024];
        for (int off = 0; off < TotalBytes; off += zeros.Length)
            Marshal.Copy(zeros, 0, _view + off, Math.Min(zeros.Length, TotalBytes - off));
        Marshal.WriteInt32(_view, OffMagic, unchecked((int)Magic));
        Marshal.WriteInt32(_view, OffVersion, (int)ChannelVersion);
        Marshal.WriteInt32(_view, OffHeaderBytes, HeaderBytes);
        Marshal.WriteInt32(_view, OffSlotCount, SlotCount);
        Thread.MemoryBarrier();
    }

    /// <summary>Publish the current format without a frame (before the
    /// first frame exists; also how `active` flips on stop).</summary>
    public void WriteFormat(VirtualCameraFormat fmt, bool active)
    {
        if (_view == IntPtr.Zero) return;
        Marshal.WriteInt32(_view, OffFrameWidth, fmt.Width);
        Marshal.WriteInt32(_view, OffFrameHeight, fmt.Height);
        Marshal.WriteInt32(_view, OffStride, fmt.Width * 4);
        Marshal.WriteInt32(_view, OffFps, fmt.Fps);
        Marshal.WriteInt32(_view, OffRotation, fmt.Rotation);
        Marshal.WriteInt32(_view, OffMirrored, fmt.Mirrored ? 1 : 0);
        Marshal.WriteInt32(_view, OffActive, active ? 1 : 0);
        Thread.MemoryBarrier();
    }

    public void SetActive(bool active)
    {
        if (_view == IntPtr.Zero) return;
        Marshal.WriteInt32(_view, OffActive, active ? 1 : 0);
        Thread.MemoryBarrier();
    }

    /// <summary>Publish one tightly packed BGRA frame. Returns the new
    /// sequence number, or 0 when the frame does not fit the channel
    /// (dropped, never truncated, never blocks).</summary>
    public long Publish(byte[] bgra, int width, int height, int fps, int rotation,
                        bool mirrored, long timestampUs)
    {
        if (_view == IntPtr.Zero || bgra is null) return 0;
        if (width <= 0 || height <= 0 || width > MaxFrameWidth || height > MaxFrameHeight)
            return 0;
        int stride = width * 4;
        if (bgra.Length < stride * height) return 0;

        long seq = Marshal.ReadInt64(_view, OffLatestSeq) + 1;
        int slotOff = SlotOffset((int)(seq % SlotCount));
        Marshal.Copy(bgra, 0, _view + slotOff + SlotOffPixels, stride * height);
        Marshal.WriteInt64(_view, slotOff + SlotOffTimestampUs, timestampUs);
        Marshal.WriteInt32(_view, slotOff + SlotOffWidth, width);
        Marshal.WriteInt32(_view, slotOff + SlotOffHeight, height);
        Marshal.WriteInt32(_view, slotOff + SlotOffStride, stride);
        WriteFormat(new VirtualCameraFormat(width, height, fps, rotation, mirrored),
                    active: true);
        Thread.MemoryBarrier();
        Marshal.WriteInt64(_view, slotOff + SlotOffSeq, seq);
        Marshal.WriteInt64(_view, OffLatestTimestampUs, timestampUs);
        Thread.MemoryBarrier();
        Marshal.WriteInt64(_view, OffLatestSeq, seq);
        return seq;
    }

    public void Dispose()
    {
        if (_view != IntPtr.Zero) { UnmapViewOfFile(_view); _view = IntPtr.Zero; }
        if (_mapping != IntPtr.Zero) { CloseHandle(_mapping); _mapping = IntPtr.Zero; }
    }

    // ---- native plumbing ----

    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool bInheritHandle;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(
        string stringSecurityDescriptor, uint stringSDRevision,
        out IntPtr securityDescriptor, out uint securityDescriptorSize);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFileMappingW(
        IntPtr hFile, ref SECURITY_ATTRIBUTES lpFileMappingAttributes,
        uint flProtect, uint dwMaximumSizeHigh, uint dwMaximumSizeLow, string lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr MapViewOfFile(
        IntPtr hFileMappingObject, uint dwDesiredAccess,
        uint dwFileOffsetHigh, uint dwFileOffsetLow, UIntPtr dwNumberOfBytesToMap);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnmapViewOfFile(IntPtr lpBaseAddress);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr hMem);
}
