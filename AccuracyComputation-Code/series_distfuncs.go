package main

import (
	"math"
)

type seriesdistfunc func([][]int64, [][]int64, int64, localdistfunc) (int64, [][]int)

func computeDiagSum(x [][]int64, y [][]int64, unused int64, dist localdistfunc) (int64, [][]int) {
	n1 := len(x)
	n2 := len(y)
	m := len(x[0])
	m2 := len(y[0])

	// fmt.Printf("n1, n2, m : %d, %d, %d\n", n1, n2, m)

	// if n1 != n2 {
	// 	fmt.Println("time series have different lengths!")
	// }

	if m != m2 {
		panic("time series elements have different dimensions!")
	}

	sum := int64(0)
	n := int(minInts(int64(n1), int64(n2)))

	for j := 0; j < n; j++ {
		sum += dist(x[j], y[j])
	}

	path := [][]int{}
	for j := 0; j < n; j++ {
		path = append(path, []int{j, j})
	}

	return sum, path
}

func findShortestPath(dists [][]int64) [][]int {
	path := [][]int{}

	cY := len(dists) - 1
	cX := len(dists[0]) - 1

	path = append(path, []int{cX, cY})

	for cX > 0 || cY > 0 {
		neighborDists := []int64{}
		if cX > 0 {
			neighborDists = append(neighborDists, dists[cY][cX-1])
		}
		if cY > 0 {
			neighborDists = append(neighborDists, dists[cY-1][cX])
		}
		if cX > 0 && cY > 0 {
			neighborDists = append(neighborDists, dists[cY-1][cX-1])
		}

		minDist := minIntFromArray(neighborDists)

		if cX > 0 && minDist == dists[cY][cX-1] {
			cX -= 1
		}
		if cY > 0 && minDist == dists[cY-1][cX] {
			cY -= 1
		}
		if cX > 0 && cY > 0 && minDist == dists[cY-1][cX-1] {
			cX -= 1
			cY -= 1
		}

		path = append(path, []int{cX, cY})
	}

	// fmt.Println(path)
	// fmt.Println(len(path))

	return path
}

func computeDTW(x [][]int64, y [][]int64, unused int64, dist localdistfunc) (int64, [][]int) {
	n1 := len(x)
	n2 := len(y)
	m := len(x[0])
	m2 := len(y[0])

	// fmt.Printf("n1, n2, m : %d, %d, %d\n", n1, n2, m)

	if m != m2 {
		panic("time series elements have different dimensions!")
	}

	dtw := make([][]int64, n2)
	for i := range dtw {
		dtw[i] = make([]int64, n1)
	}

	dtw[0][0] = int64(0)

	for j := 0; j < n2; j++ {
		for i := 0; i < n1; i++ {
			if i > 0 || j > 0 {
				cost := dist(x[i], y[j])

				min_c := int64(math.MaxInt64)
				if i > 0 {
					min_c = minInts(min_c, dtw[j][i-1])
				}
				if j > 0 {
					min_c = minInts(min_c, dtw[j-1][i])
				}
				if i > 0 && j > 0 {
					min_c = minInts(min_c, dtw[j-1][i-1])
				}

				dtw[j][i] = cost + min_c
			}
		}
	}

	return dtw[n2-1][n1-1], findShortestPath(dtw)
}

func computeERD(x [][]int64, y [][]int64, delta int64, dist localdistfunc) (int64, [][]int) {
	n1 := len(x)
	n2 := len(y)
	m := len(x[0])
	m2 := len(y[0])

	// fmt.Printf("n1, n2, m : %d, %d, %d\n", n1, n2, m)

	if m != m2 {
		panic("time series elements have different dimensions!")
	}

	edr := make([][]int64, n2)
	for i := range edr {
		edr[i] = make([]int64, n1)
	}

	edr[0][0] = 0

	for j := 0; j < n2; j++ {
		for i := 0; i < n1; i++ {
			if i > 0 || j > 0 {
				cost := int64(0)
				if dist(x[i], y[j]) > delta {
					cost = delta
				}

				min_c := int64(math.MaxInt64)
				if i > 0 {
					min_c = minInts(min_c, edr[j][i-1])
				}
				if j > 0 {
					min_c = minInts(min_c, edr[j-1][i])
				}
				if i > 0 && j > 0 {
					min_c = minInts(min_c, edr[j-1][i-1])
				}

				edr[j][i] = cost + min_c
			}
		}
	}

	return edr[n2-1][n1-1], findShortestPath(edr)
}

func computeTWED(x [][]int64, y [][]int64, lambda int64, dist localdistfunc) (int64, [][]int) {
	n1 := len(x)
	n2 := len(y)
	m := len(x[0])
	m2 := len(y[0])

	// fmt.Printf("n1, n2, m : %d, %d, %d\n", n1, n2, m)

	if m != m2 {
		panic("time series elements have different dimensions!")
	}

	twed := make([][]int64, n2)
	for i := range twed {
		twed[i] = make([]int64, n1)
	}

	twed[0][0] = 0

	for j := 0; j < n2; j++ {
		for i := 0; i < n1; i++ {
			if i > 0 || j > 0 {

				// Python code https://github.com/jzumer/pytwed/blob/master/pytwed/twed.c
				// 	float del_a = D[INDEX(i-1, j)]
				//                 + dist(arr1+((i-1)*n_feats), arr1+(i*n_feats), n_feats, degree)
				//                 + nu * (arr1_spec[i] - arr1_spec[i-1])
				//                 + lambda;
				// float del_b = D[INDEX(i, j-1)]
				//                 + dist(arr2+((j-1)*n_feats), arr2+(j*n_feats), n_feats, degree)
				//                 + nu * (arr2_spec[j] - arr2_spec[j-1])
				//                 + lambda;
				// float match = D[INDEX(i-1, j-1)]
				//                 + dist(arr1+(i*n_feats), arr2+(j*n_feats), n_feats, degree)
				//                 + dist(arr1+((i-1)*n_feats), arr2+((j-1)*n_feats), n_feats, degree)
				//                 + nu * (fabs(arr1_spec[i] - arr2_spec[j]) + fabs(arr1_spec[i-1] - arr2_spec[j-1]));

				// D[INDEX(i, j)] = fmin(match, fmin(del_a, del_b))

				min_c := int64(math.MaxInt64)
				if i > 0 {
					cand := dist(x[i], x[i-1]) + lambda + twed[j][i-1]
					min_c = minInts(min_c, cand)
				}
				if j > 0 {
					cand := dist(y[j], y[j-1]) + lambda + twed[j-1][i]
					min_c = minInts(min_c, cand)
				}
				if i > 0 && j > 0 {
					cand := dist(x[i], y[j]) + dist(x[i-1], y[j-1]) + twed[j-1][i-1]
					min_c = minInts(min_c, cand)
				}

				twed[j][i] = min_c
			}
		}
	}

	return twed[n2-1][n1-1], findShortestPath(twed)
}
