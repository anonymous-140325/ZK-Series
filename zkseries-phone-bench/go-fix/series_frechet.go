package main

import (
	"math"
)

// computeFrechet returns the discrete Frechet distance between x and y: the
// smallest, over all warping paths, of the largest local distance along the
// path. It is computeDTW with "cost + min(predecessors)" replaced by
// "max(cost, min(predecessors))". Unlike computeDTW, the first pair of elements
// counts, as in the standard definition, so that a single-element pair of
// series is at the distance of its two elements.
//
// It has the same signature as the other series distance functions, so it can
// be passed wherever a seriesdistfunc is expected; the int64 parameter is
// ignored.
func computeFrechet(x [][]int64, y [][]int64, unused int64, dist localdistfunc) (int64, [][]int) {
	n1 := len(x)
	n2 := len(y)
	m := len(x[0])
	m2 := len(y[0])

	if m != m2 {
		panic("time series elements have different dimensions!")
	}

	frechet := make([][]int64, n2)
	for i := range frechet {
		frechet[i] = make([]int64, n1)
	}

	frechet[0][0] = dist(x[0], y[0])

	for j := 0; j < n2; j++ {
		for i := 0; i < n1; i++ {
			if i > 0 || j > 0 {
				cost := dist(x[i], y[j])

				min_c := int64(math.MaxInt64)
				if i > 0 {
					min_c = minInts(min_c, frechet[j][i-1])
				}
				if j > 0 {
					min_c = minInts(min_c, frechet[j-1][i])
				}
				if i > 0 && j > 0 {
					min_c = minInts(min_c, frechet[j-1][i-1])
				}

				if cost > min_c {
					frechet[j][i] = cost
				} else {
					frechet[j][i] = min_c
				}
			}
		}
	}

	return frechet[n2-1][n1-1], findShortestPath(frechet)
}
