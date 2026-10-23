package series;

/**
 * Series distance functions for ZK-Series.
 *
 * Ported from the reference Go implementation in
 * AccuracyComputation-Code/series_distfuncs.go so that the Java tree (and
 * therefore the Android benchmark harness) computes byte-identical distances
 * to the accuracy pipeline. Every function here is a line-for-line port of its
 * Go counterpart; where the Go code has a quirk, the quirk is preserved
 * deliberately and noted.
 *
 * Indexing convention, inherited from Go: the cost matrix is addressed as
 * d[j][i] with j indexing the SECOND series (y) and i the FIRST (x). Keeping
 * that order matters -- transposing it changes the tie-breaking in
 * findShortestPath and therefore the reported coupling.
 */
public class SeriesDist {

	public enum SeriesDistType {
		SUM,
		DTW,
		FRECHET,
		ERD,
		TWED
	}

	/** A local distance between two time series elements, i.e. Go's localdistfunc. */
	public interface LocalDistFunc {
		long apply(long[] x, long[] y);
	}

	/** Distance plus the coupling (index pair sequence) that achieves it. */
	public static class Result {
		public final long distance;
		public final int[][] path;

		public Result(long distance, int[][] path) {
			this.distance = distance;
			this.path = path;
		}
	}

	public static Result distance(long[][] x, long[][] y, SeriesDistType type,
			long param, LocalDistFunc dist) {
		if (type == SeriesDistType.SUM) return diagSum(x, y, param, dist);
		if (type == SeriesDistType.DTW) return dtw(x, y, param, dist);
		if (type == SeriesDistType.FRECHET) return frechet(x, y, param, dist);
		if (type == SeriesDistType.ERD) return erd(x, y, param, dist);
		return twed(x, y, param, dist);
	}

	private static void checkDims(long[][] x, long[][] y) {
		if (x.length == 0 || y.length == 0) {
			throw new IllegalArgumentException("time series is empty!");
		}
		if (x[0].length != y[0].length) {
			throw new IllegalArgumentException("time series elements have different dimensions!");
		}
	}

	/**
	 * Diagonal sum: the sum of local distances at matching indices, over the
	 * largest number of common elements. This is the "sum" row of the paper's
	 * tables, and matches the paper's statement that for series of unequal
	 * length the sum is taken over the common prefix.
	 */
	public static Result diagSum(long[][] x, long[][] y, long unused, LocalDistFunc dist) {
		checkDims(x, y);

		int n = Math.min(x.length, y.length);

		long sum = 0;
		for (int j = 0; j < n; j++) {
			sum += dist.apply(x[j], y[j]);
		}

		int[][] path = new int[n][];
		for (int j = 0; j < n; j++) {
			path[j] = new int[] { j, j };
		}

		return new Result(sum, path);
	}

	/** Dynamic Time Warping. */
	public static Result dtw(long[][] x, long[][] y, long unused, LocalDistFunc dist) {
		checkDims(x, y);

		int n1 = x.length;
		int n2 = y.length;

		long[][] d = new long[n2][n1];
		d[0][0] = 0;

		for (int j = 0; j < n2; j++) {
			for (int i = 0; i < n1; i++) {
				if (i > 0 || j > 0) {
					long cost = dist.apply(x[i], y[j]);

					long minC = Long.MAX_VALUE;
					if (i > 0)          minC = Math.min(minC, d[j][i - 1]);
					if (j > 0)          minC = Math.min(minC, d[j - 1][i]);
					if (i > 0 && j > 0) minC = Math.min(minC, d[j - 1][i - 1]);

					d[j][i] = cost + minC;
				}
			}
		}

		return new Result(d[n2 - 1][n1 - 1], findShortestPath(d));
	}

	/**
	 * Discrete Frechet distance.
	 *
	 * Section 3.2 of the paper defines it over the same couplings as DTW but
	 * with the total function taking the maximum rather than the sum, i.e.
	 * f_delta(a_1..a_k) = max_l a_l. The recursion is therefore DTW's with
	 * "cost + min(predecessors)" replaced by "max(cost, min(predecessors))":
	 * the cost of a coupling is the largest local distance along it, and the
	 * distance is the smallest such cost over all couplings.
	 *
	 * The published Go reference implements SUM, DTW, ERD and TWED but not this
	 * one; ../AccuracyComputation-Code/series_frechet.go adds the same function to it, and
	 * SeriesDistCheck cross-validates the two like the other series distances.
	 * Both sides are also checked against a brute force search over all
	 * couplings: series.FrechetCheck here, ../AccuracyComputation-Code/frechet_check_test.go in Go.
	 */
	public static Result frechet(long[][] x, long[][] y, long unused, LocalDistFunc dist) {
		checkDims(x, y);

		int n1 = x.length;
		int n2 = y.length;

		long[][] d = new long[n2][n1];
		d[0][0] = dist.apply(x[0], y[0]);

		for (int j = 0; j < n2; j++) {
			for (int i = 0; i < n1; i++) {
				if (i > 0 || j > 0) {
					long cost = dist.apply(x[i], y[j]);

					long minC = Long.MAX_VALUE;
					if (i > 0)          minC = Math.min(minC, d[j][i - 1]);
					if (j > 0)          minC = Math.min(minC, d[j - 1][i]);
					if (i > 0 && j > 0) minC = Math.min(minC, d[j - 1][i - 1]);

					d[j][i] = Math.max(cost, minC);
				}
			}
		}

		return new Result(d[n2 - 1][n1 - 1], findShortestPath(d));
	}

	/**
	 * Edit Distance on Real sequences: as DTW, but each step costs delta if the
	 * local distance exceeds delta and nothing otherwise.
	 */
	public static Result erd(long[][] x, long[][] y, long delta, LocalDistFunc dist) {
		checkDims(x, y);

		int n1 = x.length;
		int n2 = y.length;

		long[][] d = new long[n2][n1];
		d[0][0] = 0;

		for (int j = 0; j < n2; j++) {
			for (int i = 0; i < n1; i++) {
				if (i > 0 || j > 0) {
					long cost = dist.apply(x[i], y[j]) > delta ? delta : 0;

					long minC = Long.MAX_VALUE;
					if (i > 0)          minC = Math.min(minC, d[j][i - 1]);
					if (j > 0)          minC = Math.min(minC, d[j - 1][i]);
					if (i > 0 && j > 0) minC = Math.min(minC, d[j - 1][i - 1]);

					d[j][i] = cost + minC;
				}
			}
		}

		return new Result(d[n2 - 1][n1 - 1], findShortestPath(d));
	}

	/**
	 * Time Warp Edit Distance with deletion penalty lambda and no stiffness
	 * (time shift) penalty, i.e. Marteau's TWED with nu = 0.
	 *
	 * The cost of a step depends on its direction. A step in x alone deletes
	 * x[i] at lambda + d(x[i], x[i-1]), a step in y alone deletes y[j] at
	 * lambda + d(y[j], y[j-1]), and a diagonal step matches at
	 * d(x[i], y[j]) + d(x[i-1], y[j-1]). Both deletion branches therefore take
	 * their two arguments from the SAME series. The path starts at d[0][0] = 0,
	 * not at d(x[0], y[0]), as for dtw.
	 *
	 * This reproduces the Go reference exactly, and the paper's series distance
	 * definitions (step costs a_1 = 0 and a_l by direction) describe it term for
	 * term.
	 */
	public static Result twed(long[][] x, long[][] y, long lambda, LocalDistFunc dist) {
		checkDims(x, y);

		int n1 = x.length;
		int n2 = y.length;

		long[][] d = new long[n2][n1];
		d[0][0] = 0;

		for (int j = 0; j < n2; j++) {
			for (int i = 0; i < n1; i++) {
				if (i > 0 || j > 0) {
					long minC = Long.MAX_VALUE;

					if (i > 0) {
						minC = Math.min(minC,
								dist.apply(x[i], x[i - 1]) + lambda + d[j][i - 1]);
					}
					if (j > 0) {
						minC = Math.min(minC,
								dist.apply(y[j], y[j - 1]) + lambda + d[j - 1][i]);
					}
					if (i > 0 && j > 0) {
						minC = Math.min(minC,
								dist.apply(x[i], y[j])
								+ dist.apply(x[i - 1], y[j - 1])
								+ d[j - 1][i - 1]);
					}

					d[j][i] = minC;
				}
			}
		}

		return new Result(d[n2 - 1][n1 - 1], findShortestPath(d));
	}

	/**
	 * Backtrack the coupling from the filled cost matrix.
	 *
	 * The sequence of ifs below is not an if/else chain in the Go original, so a
	 * diagonal predecessor that ties with both orthogonal neighbours advances
	 * cX and cY twice in one iteration. That behaviour is preserved here on
	 * purpose: changing it would change the reported couplings relative to the
	 * published accuracy results.
	 */
	public static int[][] findShortestPath(long[][] dists) {
		int cY = dists.length - 1;
		int cX = dists[0].length - 1;

		java.util.List<int[]> path = new java.util.ArrayList<int[]>();
		path.add(new int[] { cX, cY });

		while (cX > 0 || cY > 0) {
			long minDist = Long.MAX_VALUE;
			if (cX > 0)          minDist = Math.min(minDist, dists[cY][cX - 1]);
			if (cY > 0)          minDist = Math.min(minDist, dists[cY - 1][cX]);
			if (cX > 0 && cY > 0) minDist = Math.min(minDist, dists[cY - 1][cX - 1]);

			if (cX > 0 && minDist == dists[cY][cX - 1]) {
				cX -= 1;
			}
			if (cY > 0 && minDist == dists[cY - 1][cX]) {
				cY -= 1;
			}
			if (cX > 0 && cY > 0 && minDist == dists[cY - 1][cX - 1]) {
				cX -= 1;
				cY -= 1;
			}

			path.add(new int[] { cX, cY });
		}

		return path.toArray(new int[path.size()][]);
	}
}
