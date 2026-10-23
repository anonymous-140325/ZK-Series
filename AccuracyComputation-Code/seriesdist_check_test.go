package main

import (
	"fmt"
	"testing"
)

// Deterministic, RNG-free, identical to series.SeriesDistCheck on the Java side.
func genCheck(T int, m int, salt int) [][]int64 {
	s := make([][]int64, T)
	for t := 0; t < T; t++ {
		s[t] = make([]int64, m)
		for j := 0; j < m; j++ {
			s[t][j] = (int64(t)*7919 + int64(j)*104729 + int64(salt)*31) % 20000
		}
	}
	return s
}

func TestSeriesDistCrossCheck(t *testing.T) {
	Ts := []int{5, 17, 50}
	ms := []int{1, 3}

	locals := []localdistfunc{
		localDistanceManhattan,
		localDistanceEuclidean,
		localDistanceChebyshev,
	}
	localNames := []string{"manhattan", "euclidean", "chebyshev"}

	fns := []func([][]int64, [][]int64, int64, localdistfunc) (int64, [][]int){
		computeDiagSum, computeDTW, computeFrechet, computeERD, computeTWED,
	}
	typeNames := []string{"sum", "dtw", "frechet", "erd", "twed"}

	for _, T := range Ts {
		for _, m := range ms {
			x := genCheck(T, m, 1)
			y := genCheck(T, m, 2)
			for li := range locals {
				for ti := range fns {
					var param int64 = 1000
					d, path := fns[ti](x, y, param, locals[li])
					fmt.Printf("T=%d m=%d %s %s dist=%d pathlen=%d\n",
						T, m, localNames[li], typeNames[ti], d, len(path))
				}
			}
		}
	}
}
