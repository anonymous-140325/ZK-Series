package main

import "math/rand/v2"

func generateSeries(length int, dimension int, init int64, valrange int) [][]int64 {
	result := make([][]int64, length)
	for t := range result {
		result[t] = make([]int64, dimension)
	}

	for j := range result[0] {
		result[0][j] = init
	}

	for t := range result {
		if t > 0 {
			for j := range result[t] {
				result[t][j] = result[t-1][j] + int64(rand.IntN(2*valrange+1)-valrange)
				if result[t][j] < 0 {
					result[t][j] = 0
				}
			}
		}
	}

	return result
}

func generateErrorSeries(length int, dimension int, valrange int, reversion float64) [][]int64 {
	result := make([][]int64, dimension)
	for t := range result {
		result[t] = make([]int64, length)
	}

	for j := range result[0] {
		result[0][j] = 0
	}

	for t := range result {
		if t > 0 {
			for j := range result[t] {
				result[t][j] = int64(reversion * float64((result[t-1][j] + int64(rand.IntN(2*valrange+1)-valrange))))
			}
		}
	}

	return result
}
