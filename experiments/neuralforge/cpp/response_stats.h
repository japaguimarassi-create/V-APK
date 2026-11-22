#pragma once
#include <cstddef>

#ifdef _WIN32
  #ifdef RESPONSE_STATS_BUILD
    #define RS_API __declspec(dllexport)
  #else
    #define RS_API __declspec(dllimport)
  #endif
#else
  #define RS_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

RS_API int nf_response_stats(
    const float* data,
    std::size_t length,
    double* mean,
    double* standard_deviation,
    float* minimum,
    float* maximum,
    double* root_mean_square
);

#ifdef __cplusplus
}
#endif
