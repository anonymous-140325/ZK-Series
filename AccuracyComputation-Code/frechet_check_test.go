package main

import (
	"math/rand"
	"testing"
)

// frechetBrute enumerates every warping path from the first to the last pair
// of elements and returns the smallest, over those paths, of the largest local
// distance along one. It shares no code with computeFrechet, and is exponential
// in the series length, so it is only run on short series.
func frechetBrute(x [][]int64, y [][]int64, dist localdistfunc) int64 {
	var walk func(i, j int, worst int64) int64
	walk = func(i, j int, worst int64) int64 {
		if c := dist(x[i], y[j]); c > worst {
			worst = c
		}
		if i == len(x)-1 && j == len(y)-1 {
			return worst
		}
		best := int64(-1)
		consider := func(v int64) {
			if best < 0 || v < best {
				best = v
			}
		}
		if i+1 < len(x) {
			consider(walk(i+1, j, worst))
		}
		if j+1 < len(y) {
			consider(walk(i, j+1, worst))
		}
		if i+1 < len(x) && j+1 < len(y) {
			consider(walk(i+1, j+1, worst))
		}
		return best
	}
	return walk(0, 0, 0)
}

func frechetRandSeries(rng *rand.Rand, n int, m int) [][]int64 {
	s := make([][]int64, n)
	for t := range s {
		s[t] = make([]int64, m)
		for j := range s[t] {
			s[t][j] = rng.Int63n(1000)
		}
	}
	return s
}

// TestFrechetAgainstBruteForce checks computeFrechet against an exhaustive
// search over all warping paths, on random series of different lengths and
// dimensions and for all three local distances, and checks that every series is
// at distance 0 from itself.
//
// A check that any distance function would pass says nothing, so the same
// inputs are also given to computeDTW, which must disagree with the brute force
// at least once.
func TestFrechetAgainstBruteForce(t *testing.T) {
	rng := rand.New(rand.NewSource(20260915))
	locals := []localdistfunc{
		localDistanceManhattan,
		localDistanceEuclidean,
		localDistanceChebyshev,
	}

	const trials = 400
	dtwDiffers := 0
	for trial := 0; trial < trials; trial++ {
		n1 := 1 + rng.Intn(6)
		n2 := 1 + rng.Intn(6)
		m := 1 + rng.Intn(3)
		x := frechetRandSeries(rng, n1, m)
		y := frechetRandSeries(rng, n2, m)
		dist := locals[trial%len(locals)]

		want := frechetBrute(x, y, dist)
		got, _ := computeFrechet(x, y, 0, dist)
		if got != want {
			t.Fatalf("trial %d (|x|=%d |y|=%d m=%d): computeFrechet=%d, brute force=%d",
				trial, n1, n2, m, got, want)
		}
		if self, _ := computeFrechet(x, x, 0, dist); self != 0 {
			t.Fatalf("trial %d: distance of a series to itself is %d, not 0", trial, self)
		}
		if d, _ := computeDTW(x, y, 0, dist); d != want {
			dtwDiffers++
		}
	}
	if dtwDiffers == 0 {
		t.Fatalf("computeDTW agreed with the brute force on all %d trials, so this test cannot tell the two apart", trials)
	}
	t.Logf("%d random cases agree with brute force; computeDTW differs on %d of them", trials, dtwDiffers)
}
