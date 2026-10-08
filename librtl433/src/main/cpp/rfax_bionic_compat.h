/*
 * rfax_bionic_compat.h - Android/Bionic compatibility shims for rtl_433.
 *
 * rtl_433's rtl_tcp output sink (output_rtltcp.c) calls pthread_cancel() to
 * stop its server thread. Bionic does not implement thread cancellation, so
 * the call is redirected to a no-op via a compile definition. The rtl_tcp
 * output is not used by RF Analyzer; this only keeps the file linkable.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#ifndef RFAX_BIONIC_COMPAT_H_
#define RFAX_BIONIC_COMPAT_H_

#include <pthread.h>

int rfax_pthread_cancel(pthread_t thread);

#endif /* RFAX_BIONIC_COMPAT_H_ */
