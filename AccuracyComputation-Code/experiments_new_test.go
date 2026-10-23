package main

import (
	"fmt"
	"sort"
	"strconv"
	"testing"
)

func compareDataForRangeProofs(data [][][]int64, threshold int64, k int, seriesfunc seriesdistfunc, localfunc localdistfunc) []float64 {
	tDists := make([]int64, len(data))
	dists := make([][]int64, len(data))
	for j := range data {
		dists[j] = make([]int64, len(data))
	}

	nCorrect := 0

	percs := []float64{}

	for i := range data {
		fmt.Printf(" reading : %d\n", i)

		dists := make([]int64, len(data))
		paths := make([][][]int, len(data))

		for j := range data {
			dists[j], paths[j] = seriesfunc(data[j], data[i], 1000000, localfunc)
			path := paths[j]

			slack := threshold - dists[j]

			d := int64(0)

			costs1 := make([]int64, len(paths[j]))
			costs2 := make([]int64, len(paths[j]))

			for k := range paths[j] {
				x := data[i][path[k][0]]
				y := data[j][path[k][1]]
				d += localfunc(x, y)
				costs1[k] = totSum(x, y) - localfunc(x, y)
				costs2[k] = totEucl(x, y) - localfunc(x, y)
				if i != j && costs1[k] > costs2[k] {
					fmt.Println("!!!")
				}
				// fmt.Printf("--- %d, %d, %d: (%d, %d) slack %d \n", localfunc(x, y), totSum(x, y), totDiag(x, y), costs1[k], costs2[k], slack)
			}

			sort.Slice(costs1, func(i, j int) bool {
				return costs1[i] < costs1[j]
			})

			sort.Slice(costs2, func(i, j int) bool {
				return costs2[i] < costs2[j]
			})

			saved := 0
			remain := slack
			for k := range costs1 {
				remain -= costs1[k]
				if remain >= 0 {
					saved++
				} else {
					break
				}
			}

			if i != j && threshold > 0 {
				percs = append(percs, 100*float64(saved)/float64(len(path)))
			}

			// fmt.Println(costs)
			// fmt.Println(saved)
			fmt.Printf("%d of %d (%f%%)\n", saved, len(path), 100*float64(saved)/float64(len(path)))

			// fmt.Printf("  %d v %d length path: %d, dist: %d v. %d, %d\n", i, j, len(paths[j]), dists[j], threshold, d)
		}

		sort.Slice(dists, func(i, j int) bool {
			return dists[i] < dists[j]
		})

		tDists[i] = dists[k]
		if tDists[i] <= threshold {
			nCorrect += 1
		}
	}

	return percs

}

func processExperiment3(allData [][][][]int64, p int, seriesfunc seriesdistfunc, localfunc localdistfunc, k int) (int, int) {
	thresholds := determineThresholds(allData, k, p, seriesfunc, localfunc)

	numRangeProofsTotal := 0
	numRangeProofsReducedTotal := 0

	percs := [][]float64{}

	for i := range allData {
		fmt.Printf("user: %d\n", i)
		percs = append(percs, compareDataForRangeProofs(allData[i], thresholds[i], k, seriesfunc, localfunc))
	}
	fmt.Print("[")
	for i := range percs {
		for j := range percs[i] {
			fmt.Print(percs[i][j])
			if i < len(percs)-1 || j < len(percs[i])-1 {
				fmt.Print(", ")
			}
		}
	}

	fmt.Println("]")

	// fmt.Println("aaa")

	return numRangeProofsTotal, numRangeProofsReducedTotal
}

func experiment_range_proof_reduction(seriesfunc seriesdistfunc, localfunc localdistfunc, sheetname string, cols []int, p int, k int) (int, int) {
	allData := make([][][][]int64, 20)
	sheetnames := []string{"Orientation"}

	for i := range allData {
		allData[i] = loadXlsDataFromFiles(getFileNames("../ShakeDynamics-Dataset/Participant-"+strconv.Itoa(i+1)+"/", "", "xlsx"), cols, sheetnames)
		allData[i] = normalizeAll(allData[i])
	}

	return processExperiment3(allData, p, seriesfunc, localfunc, k)
}

func TestExperimentRangeProofReduction(t *testing.T) {
	seriesfunc := computeDiagSum
	localfunc := localDistanceManhattan

	sheetname := "Orientation"
	cols := []int{2, 5, 8}
	p := 0
	k := 3

	experiment_range_proof_reduction(seriesfunc, localfunc, sheetname, cols, p, k)

	// fmt.Printf("true positive (precision): %f\n", truePositive/totalPositive)
	// fmt.Printf("true negative (recall): %f\n", trueNegative/totalNegative)
	// fmt.Printf("false positive: %f\n", falsePositive/totalNegative)
	// fmt.Printf("false negative: %f\n", falseNegative/totalPositive)
	// fmt.Printf("accuracy: %f\n", (truePositive+trueNegative)/(totalPositive+totalNegative))
}
