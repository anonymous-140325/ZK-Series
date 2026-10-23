package main

import (
	"fmt"
	"math"
	"testing"
)

func TestLocalDistFuncs(t *testing.T) {
	series1 := generateSeries(100, 5, 100, 5)
	series2 := generateSeries(100, 5, 100, 5)

	totalManh := int64(0)
	for i := range series1 {
		totalManh += localDistanceManhattan(series1[i], series2[i])
	}

	fmt.Printf("total Manhattan : %d\n", totalManh)

	totalEuclid := 0.
	for i := range series1 {
		totalEuclid += math.Sqrt(float64(localDistanceEuclidean(series1[i], series2[i])))
	}

	fmt.Printf("total Euclidean : %f\n", totalEuclid)

	totalChebyshev := int64(0)
	for i := range series1 {
		totalChebyshev += localDistanceChebyshev(series1[i], series2[i])
	}

	fmt.Printf("total Chebyshev : %d\n", totalChebyshev)
}
