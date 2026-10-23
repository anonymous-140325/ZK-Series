package main

import (
	"fmt"
	"testing"
	"time"
)

// Reproduces the grid of Table 7 in the ZK-Series paper (m=3, T=50..300) and
// prints BOTH the per-T mean (correct) and the cumulative figure that the
// unfixed harness printed, so the two can be compared directly.
func TestPaperTable7Grid(t *testing.T) {
	Ts := []int{50, 100, 150, 200, 250, 300}
	m := 3
	N := 20

	type fn struct {
		name string
		f    func(x [][]int64, y [][]int64, p int64, d localdistfunc) (int64, [][]int)
	}
	fns := []fn{
		{"diagonal sum", computeDiagSum},
		{"DTW", computeDTW},
	}

	for _, ff := range fns {
		fmt.Printf("\n%-13s  %8s  %12s  %12s\n", ff.name, "T", "per-T (s)", "cumulative(s)")
		cum := int64(0)
		for _, T := range Ts {
			per := int64(0)
			for n := 0; n < N; n++ {
				s1 := generateSeries(T, m, 1000000, 10000)
				s2 := generateSeries(T, m, 1000000, 10000)
				st := time.Now().UnixNano()
				ff.f(s1, s2, 0, localDistanceEuclidean)
				per += time.Now().UnixNano() - st
			}
			cum += per
			fmt.Printf("%-13s  %8d  %12.6f  %12.6f\n", "", T,
				float64(per)/float64(N*1000000000),
				float64(cum)/float64(N*1000000000))
		}
	}
}
