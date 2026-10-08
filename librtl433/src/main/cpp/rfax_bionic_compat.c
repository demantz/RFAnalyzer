/*
 * rfax_bionic_compat.c - Android/Bionic compatibility shims for rtl_433.
 *
 * See rfax_bionic_compat.h. pthread_cancel() is unavailable on Android, so it
 * is replaced by this no-op for the rtl_tcp output sink.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

#include "rfax_bionic_compat.h"

int rfax_pthread_cancel(pthread_t thread)
{
    (void)thread;
    return 0;
}
