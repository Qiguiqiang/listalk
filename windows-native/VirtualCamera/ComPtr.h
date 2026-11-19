#pragma once
// Minimal COM helpers for the virtual-camera DLL — hand-rolled instead
// of WRL/ATL so the DLL's dependency surface stays exactly the MF libs
// (and so the sandbox stub pass covers everything we use).

#include <atomic>
#include <cstdint>

#include <windows.h>

// Module reference counting (defined in dllmain.cpp): every media
// source/stream object and every LockServer call holds one reference;
// DllCanUnloadNow answers from it.
void ModuleAddRef();
void ModuleRelease();

template <typename T>
class ComPtr {
public:
    ComPtr() noexcept = default;
    explicit ComPtr(T* p) noexcept : _p(p) {}
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;
    ComPtr(ComPtr&& other) noexcept : _p(other.Detach()) {}
    ComPtr& operator=(ComPtr&& other) noexcept {
        Reset(other.Detach());
        return *this;
    }
    ~ComPtr() { Reset(); }

    T* Get() const noexcept { return _p; }
    T* operator->() const noexcept { return _p; }
    explicit operator bool() const noexcept { return _p != nullptr; }
    bool operator==(std::nullptr_t) const noexcept { return _p == nullptr; }
    bool operator!=(std::nullptr_t) const noexcept { return _p != nullptr; }

    T** ReleaseAndGetAddressOf() noexcept {
        Reset();
        return &_p;
    }
    void Reset(T* p = nullptr) noexcept {
        if (_p != nullptr) _p->Release();
        _p = p;
    }
    T* Detach() noexcept {
        T* p = _p;
        _p = nullptr;
        return p;
    }
    // Take a reference on a raw pointer obtained without one
    // (e.g. a parent back-pointer returned through an out parameter).
    void CopyFrom(T* p) {
        if (p != nullptr) p->AddRef();
        Reset(p);
    }

private:
    T* _p = nullptr;
};

// Refcount plumbing for the COM classes: each class derives from its
// COM interface(s) plus ComObjectBase<Itself> and forwards
// AddRef/Release here.
template <typename Derived>
class ComObjectBase {
public:
    ULONG AddRefInternal() noexcept { return _ref.fetch_add(1) + 1; }
    ULONG ReleaseInternal() noexcept {
        const ULONG r = _ref.fetch_sub(1) - 1;
        if (r == 0) delete static_cast<Derived*>(this);
        return r;
    }

protected:
    ComObjectBase() { ModuleAddRef(); }
    virtual ~ComObjectBase() { ModuleRelease(); }

private:
    std::atomic<ULONG> _ref{1};
};
