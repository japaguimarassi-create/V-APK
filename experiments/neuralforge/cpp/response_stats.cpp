#define RESPONSE_STATS_BUILD
#include "response_stats.h"
#include <cmath>
#include <limits>

extern "C" RS_API int nf_response_stats(
    const float* data,
    std::size_t length,
    double* mean,
    double* standard_deviation,
    float* minimum,
    float* maximum,
    double* root_mean_square
) {
    if (data == nullptr || length == 0 || mean == nullptr ||
        standard_deviation == nullptr || minimum == nullptr ||
        maximum == nullptr || root_mean_square == nullptr) {
        return 0;
    }

    double running_mean = 0.0;
    double sum_squared_differences = 0.0;
    double sum_squares = 0.0;
    float low = std::numeric_limits<float>::infinity();
    float high = -std::numeric_limits<float>::infinity();

    for (std::size_t i = 0; i < length; ++i) {
        const float value = data[i];
        if (!std::isfinite(value)) return 0;

        const double x = static_cast<double>(value);
        const double delta = x - running_mean;
        running_mean += delta / static_cast<double>(i + 1);
        const double delta_after_update = x - running_mean;
        sum_squared_differences += delta * delta_after_update;
        sum_squares += x * x;

        if (value < low) low = value;
        if (value > high) high = value;
    }

    const double variance = sum_squared_differences / static_cast<double>(length);
    *mean = running_mean;
    *standard_deviation = std::sqrt(variance > 0.0 ? variance : 0.0);
    *minimum = low;
    *maximum = high;
    *root_mean_square = std::sqrt(sum_squares / static_cast<double>(length));
    return 1;
}
