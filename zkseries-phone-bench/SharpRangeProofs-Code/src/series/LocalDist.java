package series;

public class LocalDist {
	
	public enum LocalDistType {
		MANHATTAN,
		EUCLIDEAN,
		CHEBYSHEV
	}
	
	public static long distance(long[] x, long[] y, LocalDistType type) {
		if(type == LocalDistType.MANHATTAN) return manhattanDistance(x, y);
		if(type == LocalDistType.EUCLIDEAN) return euclideanDistance(x, y);
		return chebyshevDistance(x, y);
	}
	
	public static long manhattanDistance(long[] x, long[] y) {
		int m = x.length;
		int m2 = y.length;

		if (m != m2) {
			throw new IllegalArgumentException("arrays have different dimensions!");
		}

		long dist = 0;
		for (int i = 0; i < m; i++) {
			if (x[i] > y[i]) {
				dist += x[i] - y[i];
			} else {
				dist += y[i] - x[i];
			}
		}

		return dist;
	}
	
	public static long euclideanDistance(long[] x, long[] y) {
		int m = x.length;
	    int m2 = y.length;

	    if (m != m2) {
	        throw new IllegalArgumentException("arrays have different dimensions!");
	    }

	    long dist = 0;
	    for (int i = 0; i < m; i++) {
	        long diff = x[i] - y[i];
	        dist += diff * diff;
	    }

	    return dist;
	}
	
	public static long chebyshevDistance(long[] x, long[] y) {
		int m = x.length;
	    int m2 = y.length;

	    if (m != m2) {
	        throw new IllegalArgumentException("arrays have different dimensions!");
	    }

	    long dist = 0;
	    for (int i = 0; i < m; i++) {
	        long ldist = Math.abs(x[i] - y[i]);
	        if (ldist > dist) {
	            dist = ldist;
	        }
	    }

	    return dist;
	}

}
