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

#ifndef IQCONVERTER_INT16_H
#define IQCONVERTER_INT16_H

#include <stdint.h>

typedef struct {
	int len;
	int fir_index;
	int delay_index;
	int16_t old_x;
	int16_t old_y;
	int32_t old_e;
	int32_t *fir_kernel;
	int32_t *fir_queue;
	int16_t *delay_line;
} iqconverter_int16_t;

iqconverter_int16_t *iqconverter_int16_create(const int16_t *hb_kernel, int len);
void iqconverter_int16_free(iqconverter_int16_t *cnv);
void iqconverter_int16_reset(iqconverter_int16_t *cnv);
void iqconverter_int16_process(iqconverter_int16_t *cnv, int16_t *samples, int len);

#endif // IQCONVERTER_INT16_H
