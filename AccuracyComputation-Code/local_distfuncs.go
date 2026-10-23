package main

type localdistfunc func([]int64, []int64) int64

func localDistanceManhattan(x []int64, y []int64) int64 {
	m := len(x)
	m2 := len(y)

	if m != m2 {
		panic("arrays have different dimensions!")
	}

	dist := int64(0)
	for i := 0; i < m; i++ {
		if x[i] > y[i] {
			dist += x[i] - y[i]
		} else {
			dist += y[i] - x[i]
		}
	}

	return dist
}

func localDistanceEuclidean(x []int64, y []int64) int64 {
	m := len(x)
	m2 := len(y)

	if m != m2 {
		panic("arrays have different dimensions!")
	}

	dist := int64(0)
	for i := 0; i < m; i++ {
		dist += (x[i] - y[i]) * (x[i] - y[i])
	}

	return dist
}

func localDistanceChebyshev(x []int64, y []int64) int64 {
	m := len(x)
	m2 := len(y)

	if m != m2 {
		panic("arrays have different dimensions!")
	}

	dist := int64(0)
	for i := 0; i < m; i++ {
		ldist := y[i] - x[i]
		if x[i] > y[i] {
			ldist = x[i] - y[i]
		}
		if ldist > dist {
			dist = ldist
		}
	}

	return dist
}
