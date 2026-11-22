import ctypes
import os
import platform
from pathlib import Path

import numpy as np


def load_native_library(path=None):
    if path:
        candidates = [Path(path)]
    elif os.environ.get("NEURALFORGE_NATIVE_LIB"):
        candidates = [Path(os.environ["NEURALFORGE_NATIVE_LIB"])]
    else:
        root = Path(__file__).resolve().parents[2]
        system = platform.system()
        if system == "Windows":
            names = ["response_stats.dll", "libresponse_stats.dll"]
        elif system == "Darwin":
            names = ["libresponse_stats.dylib"]
        else:
            names = ["libresponse_stats.so"]
        candidates = [root / "build" / name for name in names]

    for candidate in candidates:
        if candidate.exists():
            library = ctypes.CDLL(str(candidate))
            function = library.nf_response_stats
            function.argtypes = [
                ctypes.POINTER(ctypes.c_float),
                ctypes.c_size_t,
                ctypes.POINTER(ctypes.c_double),
                ctypes.POINTER(ctypes.c_double),
                ctypes.POINTER(ctypes.c_float),
                ctypes.POINTER(ctypes.c_float),
                ctypes.POINTER(ctypes.c_double),
            ]
            function.restype = ctypes.c_int
            return library
    return None


def calculate_stats(values, library=None):
    array = np.ascontiguousarray(np.asarray(values, dtype=np.float32).reshape(-1))
    if array.size == 0:
        raise ValueError("Cannot calculate response statistics for an empty array.")

    if library is not None:
        mean = ctypes.c_double()
        std = ctypes.c_double()
        minimum = ctypes.c_float()
        maximum = ctypes.c_float()
        rms = ctypes.c_double()
        pointer = array.ctypes.data_as(ctypes.POINTER(ctypes.c_float))
        ok = library.nf_response_stats(
            pointer, array.size, ctypes.byref(mean), ctypes.byref(std),
            ctypes.byref(minimum), ctypes.byref(maximum), ctypes.byref(rms)
        )
        if not ok:
            raise FloatingPointError("C++ response statistics rejected non-finite values.")
        return {
            "count": int(array.size), "mean": float(mean.value),
            "std": float(std.value), "min": float(minimum.value),
            "max": float(maximum.value), "rms": float(rms.value),
        }

    if not np.isfinite(array).all():
        raise FloatingPointError("Response contains NaN or infinity.")
    return {
        "count": int(array.size),
        "mean": float(array.mean(dtype=np.float64)),
        "std": float(array.std(dtype=np.float64)),
        "min": float(array.min()),
        "max": float(array.max()),
        "rms": float(np.sqrt(np.mean(np.square(array, dtype=np.float64)))),
    }
