#include <jni.h>
#include <string>
#include <android/log.h>
#include "pffft.h"

#include <android/log.h>

#define LOG_TAG "PFFFTCheck"  // Replace with your desired tag
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int fftSize = -1;
PFFFT_Setup* setup = nullptr;
float* scratch = nullptr;
float* input = nullptr;
float* output = nullptr;
float* outputMag = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_performFFT(
        JNIEnv* env, jobject /* this */, jfloatArray inputArray, jfloatArray outputArray) {

    // Get array length
    jsize length = env->GetArrayLength(inputArray);

    if (fftSize != length) {
        fftSize = length;
        // Create PFFFT setup
        setup = pffft_new_setup(length / 2, PFFFT_COMPLEX);
        scratch = (float*) pffft_aligned_malloc(length  * sizeof(float));
        input = (float*) pffft_aligned_malloc(length  * sizeof(float));
        output = (float*) pffft_aligned_malloc(length  * sizeof(float));
    }

    env->GetFloatArrayRegion(inputArray, 0, length, input);

    // Perform FFT
    pffft_transform_ordered(setup, input, output, scratch, PFFFT_FORWARD);
    //pffft_transform(setup, input, output, scratch, PFFFT_FORWARD);

    env->SetFloatArrayRegion(outputArray, 0, length, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_performFFTAndLogMag(
        JNIEnv* env, jobject /* this */, jfloatArray inputArray, jfloatArray outputArray) {
    float realPower;
    float imagPower;
    int targetIndex;
    int outputLength;

    // Get array length
    jsize length = env->GetArrayLength(inputArray);
    outputLength = length / 2;

    if (fftSize != length) {
        fftSize = length;
        // Create PFFFT setup
        setup = pffft_new_setup(length / 2, PFFFT_COMPLEX);
        scratch = (float*) pffft_aligned_malloc(length  * sizeof(float));
        input = (float*) pffft_aligned_malloc(length  * sizeof(float));
        output = (float*) pffft_aligned_malloc(length  * sizeof(float));
        outputMag = (float*) pffft_aligned_malloc(outputLength  * sizeof(float));
    }

    env->GetFloatArrayRegion(inputArray, 0, length, input);

    // Perform FFT
    pffft_transform_ordered(setup, input, output, scratch, PFFFT_FORWARD);

    // Calculate the logarithmic magnitude:
    for(int i = 0; i<outputLength; i++) {
        realPower = output[2*i] / (float)outputLength;
        realPower *= realPower;
        imagPower = output[2*i+1] / (float)outputLength;
        imagPower *= imagPower;
        targetIndex = (i + outputLength/2) % outputLength;
        outputMag[targetIndex] = 10 * log10(sqrt(realPower + imagPower));
    }
    env->SetFloatArrayRegion(outputArray, 0, outputLength, outputMag);
}
