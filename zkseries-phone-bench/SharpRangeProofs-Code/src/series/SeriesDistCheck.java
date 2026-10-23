package series;

import series.SeriesDist.LocalDistFunc;
import series.SeriesDist.Result;

/**
 * Cross-validation harness: prints series distances over a deterministic input
 * so the Java port can be diffed against the Go reference implementation in
 * AccuracyComputation-Code. The generator below is duplicated exactly in the
 * Go side of this check; do not change one without the other.
 *
 *   java -cp build series.SeriesDistCheck
 */
public class SeriesDistCheck {

	/** Deterministic, RNG-free, identical to the Go check. */
	static long[][] gen(int T, int m, int salt) {
		long[][] s = new long[T][m];
		for (int t = 0; t < T; t++) {
			for (int j = 0; j < m; j++) {
				s[t][j] = ((long) t * 7919 + (long) j * 104729 + salt * 31L) % 20000;
			}
		}
		return s;
	}

	public static void main(String[] args) {
		int[] Ts = { 5, 17, 50 };
		int[] ms = { 1, 3 };

		LocalDistFunc[] locals = {
			(x, y) -> LocalDist.manhattanDistance(x, y),
			(x, y) -> LocalDist.euclideanDistance(x, y),
			(x, y) -> LocalDist.chebyshevDistance(x, y),
		};
		String[] localNames = { "manhattan", "euclidean", "chebyshev" };

		SeriesDist.SeriesDistType[] types = {
			SeriesDist.SeriesDistType.SUM,
			SeriesDist.SeriesDistType.DTW,
			SeriesDist.SeriesDistType.FRECHET,
			SeriesDist.SeriesDistType.ERD,
			SeriesDist.SeriesDistType.TWED,
		};
		String[] typeNames = { "sum", "dtw", "frechet", "erd", "twed" };

		for (int T : Ts) {
			for (int m : ms) {
				long[][] x = gen(T, m, 1);
				long[][] y = gen(T, m, 2);
				for (int li = 0; li < locals.length; li++) {
					for (int ti = 0; ti < types.length; ti++) {
						// param is delta for ERD and lambda for TWED; ignored otherwise
						long param = 1000;
						Result r = SeriesDist.distance(x, y, types[ti], param, locals[li]);
						System.out.printf("T=%d m=%d %s %s dist=%d pathlen=%d%n",
								T, m, localNames[li], typeNames[ti], r.distance, r.path.length);
					}
				}
			}
		}
	}
}
