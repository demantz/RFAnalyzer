package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.Size;

import com.mantz_it.rfanalyzer.dsp.spi.Filter;
import com.mantz_it.rfanalyzer.dsp.spi.Window;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Created by Pavel on 04.12.2016.
 */

public class Utils {
protected static final int MAXITERATIONS = 40;
protected static final int GRIDDENSITY = 16;

protected static final int POSITIVE = 0;
protected static final int NEGATIVE = 1;

public static double mean(@Size(min = 1) float[] data) {
    double mean = 0;
    for (int i = 0; i < data.length; ++i) {
        mean += data[i];
    }
    mean /= data.length;
    return mean;
}


public static double mean(Iterable<Float> data) {
    double mean = 0;
    int cnt = 0;
    for (Float n : data) {
        mean += n;
        cnt++;
    }
    if (cnt != 0)
        mean /= cnt;
    return mean;
}

public static double mean(float[] data, int offset, int stride) {
    if (offset >= data.length)
        return 0;
    stride++;
    double mean = 0;
    int cnt = 0;
    for (int i = offset; i < data.length; i += stride) {
        mean += data[i];
        cnt++;
    }
    mean /= cnt;
    return mean;
}

public static float max(@Size(min=1) float... n) {
    float max = n[0];
    for (int i = 0; i < n.length; ++i) {
        if (n[i] > max) max = n[i];
    }
    return max;
}

public static double max(@Size(min=1)double... n) {
    double max = n[0];
    for (int i = 0; i < n.length; ++i) {
        if (n[i] > max) max = n[i];
    }
    return max;
}

public static int max(@Size(min=1)int... n) {
    int max = n[0];
    for (int i = 0; i < n.length; ++i) {
        if (n[i] > max) max = n[i];
    }
    return max;
}

public static long max(@Size(min=1)long... n) {
    long max = n[0];
    for (int i = 0; i < n.length; ++i) {
        if (n[i] > max) max = n[i];
    }
    return max;
}

public static short max(@Size(min=1)short... n) {
    short max = n[0];
    for (int i = 0; i < n.length; ++i) {
        if (n[i] > max) max = n[i];
    }
    return max;
}

/**
 * Calculates the optimal (in the Chebyshev/minimax sense)
 * FIR filter impulse response given a set of band edges,
 * the desired response on those bands, and the weight given to
 * the error in those bands.
 *
 * @return - true on success, false on failure to converge
 * @numtaps - Number of filter coefficients
 * @numband - Number of bands in filter specification
 * @bands[] - User-specified band edges [2 * numband]
 * @des[] - User-specified band responses [2 * numband]
 * @weight[] - User-specified error weights [numband]
 * @type - Type of filter
 * <p>
 * OUTPUT:
 * -------
 * @h[] - Impulse response of final filter [numtaps]
 **/
// fixme: javadoc
// todo: rewrite to return array, we throw exception on failure.
protected static int remez(
        final double h[],
        final int numtaps,
        final int numband,
        final double bands[],
        final double des[],
        final double weight[],
        final Filter.Type type,
        final int griddensity
) throws ConvergenceException {

    double[] E;
    Grid grid;
    int i, iter, gridsize, r;
    int[] Ext;
    double[] taps;
    double c;
    double[] x, y, ad;
    int symmetry;

    switch (type) {
        case BANDPASS:
            symmetry = POSITIVE;
            break;
        default:
            symmetry = NEGATIVE;
            break;
    }

    r = numtaps / 2;                  /* number of extrema */
    if ((numtaps & 1) == 1 && symmetry == POSITIVE)
        r++;
      /*
       * Predict dense grid size in advance for memory allocation
       *   .5 is so we round up, not truncate
       */
    gridsize = 0;
    for (i = 0; i < numband; i++) {
        gridsize += (int) (2 * r * griddensity * (bands[2 * i + 1] - bands[2 * i]) + .5);
    }
    if (symmetry == NEGATIVE) {
        gridsize--;
    }

      /*
       * Dynamically allocate memory for arrays with proper sizes
       */
    grid = new Grid(gridsize);
    E = new double[gridsize];
    Ext = new int[r + 1];
    taps = new double[r + 1];
    x = new double[r + 1];
    y = new double[r + 1];
    ad = new double[r + 1];

      /*
       * Create dense frequency grid
       */
    createDenseGrid(r, numtaps, numband, bands, des, weight,
            grid, symmetry, griddensity);
    initialGuess(r, Ext, gridsize);

      /*
       * For Differentiator: (fix grid)
       */
    if (type == Filter.Type.DIFFERENTIATOR) {
        for (i = 0; i < gridsize; i++) {
      /* responses[i] = responses[i]*grid[i]; */
            if (grid.response[i] > 0.0001)
                grid.weights[i] = grid.weights[i] / grid.grid[i];
        }
    }

      /*
       * For odd or Negative symmetry filters, alter the
       * responses[] and weights[] according to Parks McClellan
       */
    if (symmetry == POSITIVE) {
        if (numtaps % 2 == 0) {
            for (i = 0; i < gridsize; i++) {
                c = Math.cos(Math.PI * grid.grid[i]);
                grid.response[i] /= c;
                grid.weights[i] *= c;
            }
        }
    } else {
        if ((numtaps & 1) != 0) {
            for (i = 0; i < gridsize; i++) {
                c = Math.sin(2 * Math.PI * grid.grid[i]);
                grid.response[i] /= c;
                grid.weights[i] *= c;
            }
        } else {
            for (i = 0; i < gridsize; i++) {
                c = Math.sin(Math.PI * grid.grid[i]);
                grid.response[i] /= c;
                grid.weights[i] *= c;
            }
        }
    }

      /*
       * Perform the Remez Exchange algorithm
       */
    for (iter = 0; iter < MAXITERATIONS; iter++) {
        calc_parms(r, Ext, grid, ad, x, y);
        calc_error(r, ad, x, y, grid, E);
        search(r, Ext, gridsize, E);

        //for (int i = 0; i <= r; i++)
        //assert (Ext[i] < gridsize);
        if (isConverged(r, Ext, E))
            break;
    }

    calc_parms(r, Ext, grid, ad, x, y);

      /*
       * Find the 'taps' of the filter for use with Frequency
       * Sampling.  If odd or Negative symmetry, fix the taps
       * according to Parks McClellan
       */
    for (i = 0; i <= numtaps / 2; i++) {
        if (symmetry == POSITIVE) {
            if ((numtaps & 1) != 0)
                c = 1;
            else
                c = Math.cos(Math.PI * (double) i / numtaps);
        } else {
            if ((numtaps & 1) != 0)
                c = Math.sin(2 * Math.PI * (double) i / numtaps);
            else
                c = Math.sin(Math.PI * (double) i / numtaps);
        }
        taps[i] = compute_A((double) i / numtaps, r, ad, x, y) * c;
    }

      /*
       * Frequency sampling design with calculated taps
       */
    freq_sample(numtaps, taps, h, symmetry);

    return iter < MAXITERATIONS ? 0 : -1;
}


/*********************
 * freq_sample
 * ============
 * Simple frequency sampling algorithm to determine the impulse
 * response h[] from A's found in compute_A
 * <p>
 * <p>
 * INPUT:
 * ------
 * int      N        - Number of filter coefficients
 * double   A[]      - Sample points of desired response [N/2]
 * int      symmetry - Symmetry of desired filter
 * <p>
 * OUTPUT:
 * -------
 * double h[] - Impulse Response of final filter [N]
 *********************/
// fixme: javadoc
protected static void
freq_sample(int N, double A[], double h[], int symm) {
    int n, k;
    double x, val, M;

    M = (N - 1.0) / 2.0;
    if (symm == POSITIVE) {
        if ((N & 1) != 0) {
            for (n = 0; n < N; n++) {
                val = A[0];
                x = 2 * Math.PI * (n - M) / N;
                for (k = 1; k <= M; k++)
                    val += 2.0 * A[k] * Math.cos(x * k);
                h[n] = val / N;
            }
        } else {
            for (n = 0; n < N; n++) {
                val = A[0];
                x = 2 * Math.PI * (n - M) / N;
                for (k = 1; k <= (N / 2 - 1); k++)
                    val += 2.0 * A[k] * Math.cos(x * k);
                h[n] = val / N;
            }
        }
    } else {
        if ((N & 1) != 0) {
            for (n = 0; n < N; n++) {
                val = 0;
                x = 2 * Math.PI * (n - M) / N;
                for (k = 1; k <= M; k++)
                    val += 2.0 * A[k] * Math.sin(x * k);
                h[n] = val / N;
            }
        } else {
            for (n = 0; n < N; n++) {
                val = A[N / 2] * Math.sin(Math.PI * (n - M));
                x = 2 * Math.PI * (n - M) / N;
                for (k = 1; k <= (N / 2 - 1); k++)
                    val += 2.0 * A[k] * Math.sin(x * k);
                h[n] = val / N;
            }
        }
    }
}

/*******************
 * isConverged
 * ========
 * Checks to see if the error function is small enough to consider
 * the result to have converged.
 * <p>
 * INPUT:
 * ------
 * int    r     - 1/2 the number of filter coeffiecients
 * int    Ext[] - Indexes to extremal frequencies [r+1]
 * double E[]   - Error function on the dense grid [gridsize]
 * <p>
 * OUTPUT:
 * -------
 * Returns 1 if the result converged
 * Returns 0 if the result has not converged
 ********************/
// fixme: javadoc
protected static boolean
isConverged(int r, int Ext[], double E[]) {
    int i;
    double min, max, current;

    min = max = Math.abs(E[Ext[0]]);
    for (i = 1; i <= r; i++) {
        current = Math.abs(E[Ext[i]]);
        if (current < min)
            min = current;
        if (current > max)
            max = current;
    }
    return (((max - min) / max) < 0.0001);
}

/********************
 * initialGuess
 * ==============
 * Places Extremal Frequencies evenly throughout the dense grid.
 * <p>
 * <p>
 * INPUT:
 * ------
 * int r        - 1/2 the number of filter coefficients
 * int gridsize - Number of elements in the dense frequency grid
 * <p>
 * OUTPUT:
 * -------
 * int ext[]    - Extremal indexes to dense frequency grid [r+1]
 ********************/
// fixme: javadoc
protected static void
initialGuess(final int r, final int[] Ext, final int gridsize) {
    int i;

    for (i = 0; i <= r; i++)
        Ext[i] = i * (gridsize - 1) / r;
}

/*********************
 * compute_A
 * ==========
 * Using values calculated in calc_parms, compute_A calculates the
 * actual filter response at a given frequency (freq).  Uses
 * eq 7.133a from Oppenheim & Schafer.
 * <p>
 * <p>
 * INPUT:
 * ------
 * double freq - Frequency (0 to 0.5) at which to calculate A
 * int    r    - 1/2 the number of filter coefficients
 * double ad[] - 'b' in Oppenheim & Schafer [r+1]
 * double x[]  - [r+1]
 * double y[]  - 'C' in Oppenheim & Schafer [r+1]
 * <p>
 * OUTPUT:
 * -------
 * Returns double value of A[freq]
 *********************/
// fixme: javadoc
protected static double
compute_A(double freq, int r, double ad[], double x[], double y[]) {
    int i;
    double xc, c, denom, numer;

    denom = numer = 0;
    xc = Math.cos(2 * Math.PI * freq);
    for (i = 0; i <= r; i++) {
        c = xc - x[i];
        if (Math.abs(c) < 1.0e-7) {
            numer = y[i];
            denom = 1;
            break;
        }
        c = ad[i] / c;
        denom += c;
        numer += c * y[i];
    }
    return numer / denom;
}

/************************
 * calc_error
 * ===========
 * Calculates the Error function from the desired frequency response
 * on the dense grid (D[]), the weight function on the dense grid (W[]),
 * and the present response calculation (A[])
 * <p>
 * <p>
 * INPUT:
 * ------
 * int    r      - 1/2 the number of filter coefficients
 * double ad[]   - [r+1]
 * double x[]    - [r+1]
 * double y[]    - [r+1]
 * int grid.size  - Number of elements in the dense frequency grid
 * double grid.grid[] - Frequencies on the dense grid [gridsize]
 * double grid.response[]    - Desired response on the dense grid [gridsize]
 * double grid.weights[]    - Weight function on the desnse grid [gridsize]
 * <p>
 * OUTPUT:
 * -------
 * double E[]    - Error function on dense grid [gridsize]
 ************************/
// fixme: javadoc
protected static void
calc_error(int r, double ad[], double x[], double y[],
           Grid grid, double E[]) {
    int i;
    double A;

    for (i = 0; i < grid.size; i++) {
        A = compute_A(grid.grid[i], r, ad, x, y);
        E[i] = grid.weights[i] * (grid.response[i] - A);
    }
}

/************************
 * search
 * ========
 * Searches for the maxima/minima of the error curve.  If more than
 * r+1 extrema are found, it uses the following heuristic (thanks
 * Chris Hanson):
 * 1) Adjacent non-alternating extrema deleted first.
 * 2) If there are more than one excess extrema, delete the
 * one with the smallest error.  This will create a non-alternation
 * condition that is fixed by 1).
 * 3) If there is exactly one excess extremum, delete the smaller
 * of the first/last extremum
 * <p>
 * <p>
 * INPUT:
 * ------
 * int    r        - 1/2 the number of filter coefficients
 * int    Ext[]    - Indexes to grid.grid[] of extremal frequencies [r+1]
 * int    gridsize - Number of elements in the dense frequency grid
 * double E[]      - Array of error values.  [gridsize]
 * OUTPUT:
 * -------
 * int    Ext[]    - New indexes to extremal frequencies [r+1]
 ************************/
// fixme: javadoc
protected static void
search(int r, int Ext[],
       int gridsize, double E[]) throws ConvergenceException {
    int i, j, k, l, extra;     /* Counters */
    int up, alt;
    int[] foundExt;             /* Array of found extremals */

      /*
       * Allocate enough space for found extremals.
       */
    foundExt = new int[2 * r];
    k = 0;

      /*
       * Check for extremum at 0.
       */
    if ((E[0] > 0.0 && E[0] > E[1])
            || (E[0] < 0.0 && E[0] < E[1]))
        foundExt[k++] = 0;

      /*
       * Check for extrema inside dense grid
       */
    for (i = 1; i < gridsize - 1; i++) {
        if ((E[i] >= E[i - 1] && E[i] > E[i + 1] && E[i] > 0.0)
                || (E[i] <= E[i - 1] && E[i] < E[i + 1] && E[i] < 0.0)
                ) {
            // PAK: we sometimes get too many extremal frequencies
            if (k >= 2 * r) {
                foundExt = null;
                throw new ConvergenceException("Remez: too many extremals -- cannot continue");

            }
            foundExt[k++] = i;
        }
    }

      /*
       * Check for extremum at 0.5
       */
    j = gridsize - 1;
    if ((E[j] > 0.0 && E[j] > E[j - 1])
            || (E[j] < 0.0 && E[j] < E[j - 1])) {
        if (k >= 2 * r) {
            throw new ConvergenceException("Remez: too many extremals -- cannot continue");

        }
        foundExt[k++] = j;
    }

    // PAK: we sometimes get not enough extremal frequencies
    if (k < r + 1) {
        throw new ConvergenceException("Remez: insufficient extremals -- cannot continue");
    }

      /*
       * Remove extra extremals
       */
    extra = k - (r + 1);

    assert (extra >= 0);

    while (extra > 0) {
        if (E[foundExt[0]] > 0.0)
            up = 1;                /* first one is a maxima */
        else
            up = 0;                /* first one is a minima */

        l = 0;
        alt = 1;
        for (j = 1; j < k; j++) {
            if (Math.abs(E[foundExt[j]]) < Math.abs(E[foundExt[l]]))
                l = j;               /* new smallest error. */
            if (up != 0 && E[foundExt[j]] < 0.0)
                up = 0;             /* switch to a minima */
            else if (up == 0 && E[foundExt[j]] > 0.0)
                up = 1;             /* switch to a maxima */
            else {
                alt = 0;
                // PAK: break now and you will delete the smallest overall
                // extremal.  If you want to delete the smallest of the
                // pair of non-alternating extremals, then you must do:
                //
                // if(Math.abs(E[foundExt[j]]) < Math.abs(E[foundExt[j-1]])) l=j;
                // else l=j-1;
                break;              /* Ooops, found two non-alternating */
            }                     /* extrema.  Delete smallest of them */
        }  /* if the loop finishes, all extrema are alternating */

	/*
	 * If there's only one extremal and all are alternating,
	 * delete the smallest of the first/last extremals.
	 */
        if (alt != 0 && extra == 1) {
            if (Math.abs(E[foundExt[k - 1]]) < Math.abs(E[foundExt[0]]))
		/* Delete last extremal */
                l = k - 1;
                // PAK: changed from l = foundExt[k-1];
            else
		/* Delete first extremal */
                l = 0;
            // PAK: changed from l = foundExt[0];
        }

        for (j = l; j < k - 1; j++) {       /* Loop that does the deletion */
            foundExt[j] = foundExt[j + 1];
            assert (foundExt[j] < gridsize);
        }
        k--;
        extra--;
    }

    for (i = 0; i <= r; i++) {
        assert (foundExt[i] < gridsize);
        Ext[i] = foundExt[i];       /* Copy found extremals to Ext[] */
    }
}

/***********************
 * calc_parms
 * ===========
 * <p>
 * <p>
 * INPUT:
 * ------
 * int    r      - 1/2 the number of filter coefficients
 * int    Ext[]  - Extremal indexes to dense frequency grid [r+1]
 * double Grid[] - Frequencies (0 to 0.5) on the dense grid [gridsize]
 * double grid.response[]    - Desired response on the dense grid [gridsize]
 * double grid.weights[]    - Weight function on the dense grid [gridsize]
 * <p>
 * OUTPUT:
 * -------
 * double ad[]   - 'b' in Oppenheim & Schafer [r+1]
 * double x[]    - [r+1]
 * double y[]    - 'C' in Oppenheim & Schafer [r+1]
 ***********************/
// fixme: javadoc
protected static void
calc_parms(
        final int r,
        final int Ext[],
        Grid grid,
        final double ad[],
        final double x[],
        final double y[]) {
    int i, j, k, ld;
    double sign, xi, delta, denom, numer;

      /*
       * Find x[]
       */
    for (i = 0; i <= r; i++)
        x[i] = Math.cos(2 * Math.PI * grid.grid[Ext[i]]);

      /*
       * Calculate ad[]  - Oppenheim & Schafer eq 7.132
       */
    ld = (r - 1) / 15 + 1;         /* Skips around to avoid round errors */
    for (i = 0; i <= r; i++) {
        denom = 1.0;
        xi = x[i];
        for (j = 0; j < ld; j++) {
            for (k = j; k <= r; k += ld)
                if (k != i)
                    denom *= 2.0 * (xi - x[k]);
        }
        if (Math.abs(denom) < 0.00001)
            denom = 0.00001;
        ad[i] = 1.0 / denom;
    }

      /*
       * Calculate delta  - Oppenheim & Schafer eq 7.131
       */
    numer = denom = 0;
    sign = 1;
    for (i = 0; i <= r; i++) {
        numer += ad[i] * grid.response[Ext[i]];
        denom += sign * ad[i] / grid.weights[Ext[i]];
        sign = -sign;
    }
    delta = numer / denom;
    sign = 1;

      /*
       * Calculate y[]  - Oppenheim & Schafer eq 7.133b
       */
    for (i = 0; i <= r; i++) {
        y[i] = grid.response[Ext[i]] - sign * delta / grid.weights[Ext[i]];
        sign = -sign;
    }
}

public static double[] Parks_McCellan_Remez(
        final int order,
        final double[] arg_bands,
        final double[] arg_amplitudes,
        final double[] arg_errorWeights,
        final Filter.Type filterType
) throws ConvergenceException {
    return Parks_McCellan_Remez(order, arg_bands, arg_amplitudes, arg_errorWeights, filterType, 16);
}

@NonNull
public static double[] Parks_McCellan_Remez(
        @IntRange(from = 3) final int order,
        @Size(multiple = 2, min = 2) final double[] arg_bands,
        @Size(multiple = 2, min = 2) final double[] arg_amplitudes,
        @Nullable @Size(min = 1) final double[] arg_errorWeights,
        final Filter.Type filterType,
        @IntRange(from = 16) final int gridDensity
) throws ConvergenceException {
    String LOGTAG = "Parks_McCellan_Remez";
    int tapsCnt = order + 1;
    if (tapsCnt < 4)
        throw new IllegalArgumentException(LOGTAG + " number of taps must be >= 3");

    int numbands = arg_bands.length >> 1;
    double[] bands = Arrays.copyOf(arg_bands, numbands << 1);
    if (numbands < 1 || (bands.length & 1) == 1) // fixme: skip second check?
        throw new IllegalArgumentException(LOGTAG + " must have an even number of band edges");

    for (int i = 1; i < bands.length; i++) {
        if (bands[i] < bands[i - 1])
            throw new IllegalArgumentException(LOGTAG + " band edges must be nondecreasing");
    }

    if (bands[0] < 0 || bands[bands.length - 1] > 1)
        throw new IllegalArgumentException(LOGTAG + " band edges must be in the range [0,1]");

    // Divide by 2 to fit with the implementation that uses a
    // sample rate of [0, 0.5] instead of [0, 1.0]
    for (int i = 0; i < 2 * numbands; i++)
        bands[i] /= 2;

    double[] response = Arrays.copyOf(arg_amplitudes, numbands << 1);
    if (arg_amplitudes.length != bands.length)
        throw new IllegalArgumentException(LOGTAG + " must have one response magnitude for each band edge");

    for (int i = 0; i < 2 * numbands; i++)
        response[i] = arg_amplitudes[i];

    double[] weight = new double[numbands];
    Arrays.fill(weight, 1);

    if (arg_errorWeights != null && arg_errorWeights.length > 0) {
        if (arg_errorWeights.length != numbands)
            throw new IllegalArgumentException(LOGTAG + " need one weight for each band [=length(band)/2]");
        for (int i = 0; i < numbands; i++)
            weight[i] = arg_errorWeights[i];
    }

			/*int itype = 0;
			if (filter_type == "bandpass")
				itype = BANDPASS;
			else if (filter_type == "differentiator")
				itype = DIFFERENTIATOR;
			else if (filter_type == "hilbert")
				itype = HILBERT;
			else
				throw new IllegalArgumentException(LOGTAG+" unknown ftype '" + filter_type + "'");
			*/
    if (gridDensity < 16)
        throw new IllegalArgumentException(LOGTAG + " grid_density is too low; must be >= 16");

    double[] coeff = new double[tapsCnt + 5]; // FIXME: why + 5?
    Utils.remez(coeff, tapsCnt, numbands, bands, response, weight, filterType, gridDensity);

    return Arrays.copyOf(coeff, tapsCnt);
}

protected static double
bessi0(double x) {
    double ax, ans;
    double y;
    ax = Math.abs(x);
    if (ax < 3.75) {
        y = x / 3.75;
        y *= y;
        ans = 1.0 + y * (3.5156229 + y * (3.0899424 + y * (1.2067492 + y * (0.2659732 + y * (0.360768e-1 + y * 0.45813e-2)))));
    } else {
        y = 3.75 / ax;
        ans = (Math.exp(ax) / Math.sqrt(ax)) * (0.39894228 + y * (0.1328592e-1 + y * (0.225319e-2 + y * (-0.157565e-2 + y * (0.916281e-2 + y * (-0.2057706e-1 + y * (0.2635537e-1 + y * (-0.1647633e-1 + y * 0.392377e-2))))))));
    }
    return ans;
}

public static int
tapsCount(double sampling_freq,
          double transition_width, // this is frequency, not relative frequency
          double attenuation_dB) {
    // Based on formula from Multirate Signal Processing for
    // Communications Systems, fredric j harris
    int ntaps = (int) (attenuation_dB * sampling_freq / (22.0 * transition_width));
    if ((ntaps & 1) == 0)    // if even...
        ntaps++;        // ...make odd
    return ntaps;
}

public static int
tapsCount(double sampling_freq,
          double transition_width,
          Window.Type window,
          double beta) {
    double a = window.maxAttenuation(beta);
    int ntaps = (int) (a * sampling_freq / (22.0 * transition_width));
    if ((ntaps & 1) == 0)    // if even...
        ntaps++;        // ...make odd

    return ntaps;
}

@NonNull
public static float[] window(Window.Type type, int ntaps, double beta) {
    return type.build(ntaps, (int) type.maxAttenuation(beta), beta);
}

/*******************
 * createDenseGrid
 * =================
 * <p>
 * Creates the dense grid of frequencies from the specified bands.
 * Also creates the Desired Frequency Response function (grid.response[]) and
 * the Weight function (grid.weights[]) on that dense grid
 * <p>
 * <p>
 * INPUT:
 * ------
 * int      r        - 1/2 the number of filter coefficients
 * int      numtaps  - Number of taps in the resulting filter
 * int      numband  - Number of bands in user specification
 * double   bands[]  - User-specified band edges [2*numband]
 * double   des[]    - Desired response per band [2*numband]
 * double   weight[] - Weight per band [numband]
 * int      symmetry - Symmetry of filter - used for grid check
 * int      griddensity
 * <p>
 * OUTPUT:
 * -------
 * int    gridsize   - Number of elements in the dense frequency grid
 * double grid.grid[]     - Frequencies (0 to 0.5) on the dense grid [gridsize]
 * double grid.response[]        - Desired response on the dense grid [gridsize]
 * double grid.weights[]        - Weight function on the dense grid [gridsize]
 *******************/
// fixme: javadoc
protected static void
createDenseGrid(
        final int r,
        final int numtaps,
        final int numband,
        @Size(multiple = 2, min = 2) final double bands[],
        @Size(multiple = 2, min = 2) final double des[],
        final double weight[],
        final Grid grid,
        final int symmetry,
        final int griddensity) {
    int i, j, k, band;
    double delf, lowf, highf, grid0;

    delf = 0.5 / (griddensity * r);

      /*
       * For differentiator, hilbert,
       *   symmetry is odd and grid.grid[0] = max(delf, bands[0])
       */
    grid0 = (symmetry == NEGATIVE) && (delf > bands[0]) ? delf : bands[0];

    j = 0;
    for (band = 0; band < numband; band++) {
        lowf = (band == 0 ? grid0 : bands[2 * band]);
        highf = bands[2 * band + 1];
        k = (int) ((highf - lowf) / delf + 0.5);   /* .5 for rounding */
        for (i = 0; i < k; i++) {
            grid.response[j] = des[2 * band] + i * (des[2 * band + 1] - des[2 * band]) / (k - 1);
            grid.weights[j] = weight[band];
            grid.grid[j] = lowf;
            lowf += delf;
            j++;
        }
        grid.grid[j - 1] = highf;
    }

      /*
       * Similar to above, if odd symmetry, last grid point can't be .5
       *  - but, if there are even taps, leave the last grid point at .5
       */
    if ((symmetry == NEGATIVE) &&
            (grid.grid[grid.size - 1] > (0.5 - delf)) &&
            ((numtaps & 1) != 0)) {
        grid.grid[grid.size - 1] = 0.5 - delf;
    }
}

protected static class Grid {
    public final int size;
    public final double[] grid;
    public final double[] response;
    public final double[] weights;

    /*
    public Grid(int size, double[] grid, double[] response, double[] weights) {
        this.size = size;
        if (grid.length != response.length
            || response.length != weights.length
            || weights.length != size)
            throw new IndexOutOfBoundsException();
        this.grid = grid;
        this.response = response;
        this.weights = weights;
    }*/
    public Grid(int size) {
        this.size = size;
        grid = new double[size];
        response = new double[size];
        weights = new double[size];
    }
}

public static final float sum(Iterable<Float> n) {
    float sum = 0;
    for (float x : n)
        sum += x;
    return sum;
}

public static int prod(ArrayList<Integer> factors) {
    int f = 1;
    for (int factor : factors) {
        f *= factor;
    }
    return f;
}

public static int prod2(ArrayList<Integer> factors) {
    int f = 1;
    for (Integer factor : factors) {
        f *= factor;
    }
    return f;
}

public static int prod(Iterable<Integer> factors) {
    int f = 1;
    for (int factor : factors) {
        f *= factor;
    }
    return f;
}

public static int prod(int[] factors) {
    int f = 1;
    for (int factor : factors) {
        f *= factor;
    }
    return f;
}


public static int filterFreqResponse_R(@NonNull float[] taps, @NonNull double[] amp, @NonNull double[] phase) {
    int cnt = Math.min(amp.length, phase.length);
    double freq_step = 2 * Math.PI / cnt;
    for (int i = 0; i < cnt; ++i) {
        amp[i] = 0;
        phase[i] = 0;
        double freq = freq_step * (i - cnt / 2);
        for (int n = 0; n < taps.length; ++n) {
            amp[i] += taps[n] * Math.cos(freq * n);
            phase[i] += taps[n] * Math.sin(freq * n);
        }
    }
    return cnt;
}
}
