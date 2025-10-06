/*
Copyright (C) 2014-2025, Youssef Touil <youssef@airspy.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to use,
copy, modify, merge, and distribute the Software exclusively as part of the 
Airspy ecosystem, which includes Airspy-branded hardware, official tools, and
associated software directly approved or maintained by the original authors.

Any redistribution, publication, sublicensing, or commercial use outside the
Airspy ecosystem is strictly prohibited without prior written consent from the 
copyright holders.

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software used within the Airspy ecosystem.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE, PROVIDED SUCH USE REMAINS WITHIN THE AIRSPY ECOSYSTEM.
*/

#ifndef IQCONVERTER_FLOAT_H
#define IQCONVERTER_FLOAT_H

#include <stdint.h>

#define IQCONVERTER_NZEROS 2
#define IQCONVERTER_NPOLES 2

typedef struct {
	float avg;
	float hbc;
	int len;
	int fir_index;
	int delay_index;
	float *fir_kernel;
	float *fir_queue;
	float *delay_line;
} iqconverter_float_t;

iqconverter_float_t *iqconverter_float_create(const float *hb_kernel, int len);
void iqconverter_float_free(iqconverter_float_t *cnv);
void iqconverter_float_reset(iqconverter_float_t *cnv);
void iqconverter_float_process(iqconverter_float_t *cnv, float *samples, int len);

#endif // IQCONVERTER_FLOAT_H
