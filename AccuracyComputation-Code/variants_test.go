package main

// Accuracy of the discrete Frechet distance and the EDR variant (ERD), with k = 1 and
// q = 0, 1, 2, for the three case studies, next to the best published configuration.
// ERD's eps is scanned as fractions of the median local distance between random
// elements of the study's readings.
//
//   go test -run TestVariants -timeout 240m .

import (
	"fmt"
	"math/rand"
	"os"
	"sort"
	"strconv"
	"testing"
)

func loadStudy(name string) [][][][]int64 {
	switch name {
	case "blow":
		names := participantNames(50)
		all := make([][][][]int64, len(names))
		for i, s := range names {
			all[i] = loadCsvDataFromFiles(getFileNames("../BlowAcoustics-Dataset/"+s+"/", "", "csv"), []int{1})
			all[i] = smoothAll(normalizeAll(all[i]), 8, 4)
		}
		return all
	case "shake":
		all := make([][][][]int64, 20)
		for i := range all {
			all[i] = loadXlsDataFromFiles(getFileNames("../ShakeDynamics-Dataset/Participant-"+strconv.Itoa(i+1)+"/", "", "xlsx"), []int{2, 5, 8}, []string{"Orientation"})
		}
		return all
	default:
		names := participantNames(20)
		all := make([][][][]int64, len(names))
		for i, s := range names {
			all[i] = nullShiftAll(loadCsvDataFromFiles(getFileNames("../FacialLandmark-Dataset/"+s+"/", "", "csv"), get2dPoints(append([]int{}, faceLandmarks...))))
		}
		return all
	}
}

func medianLocal(all [][][][]int64, lf localdistfunc) int64 {
	r := rand.New(rand.NewSource(1))
	pick := func() []int64 {
		u := all[r.Intn(len(all))]
		x := u[r.Intn(len(u))]
		return x[r.Intn(len(x))]
	}
	d := make([]int64, 20000)
	for i := range d {
		d[i] = lf(pick(), pick())
	}
	sort.Slice(d, func(a, b int) bool { return d[a] < d[b] })
	return d[len(d)/2]
}

func TestVariants(t *testing.T) {
	out, err := os.Create("variants.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	locals := []struct {
		name string
		lf   localdistfunc
	}{{"d1", localDistanceManhattan}, {"d2", localDistanceEuclidean}, {"dinf", localDistanceChebyshev}}
	for _, study := range []string{"blow", "shake", "face"} {
		all := loadStudy(study)
		names := make([]string, len(all))
		for i := range names {
			names[i] = "U" + strconv.Itoa(i)
		}
		row := func(label string, sf seriesdistfunc, lf localdistfunc) {
			line := fmt.Sprintf("%-6s %-22s", study, label)
			for p := 0; p <= 2; p++ {
				_, fp, _, fn, totP, totN := processExperiment1(all, p, sf, lf, 1, names)
				line += fmt.Sprintf("   q=%d %.4f / %.4f", p, fp/totN, fn/totP)
			}
			fmt.Fprintln(out, line)
			out.Sync()
		}
		for _, l := range locals {
			row("Frechet "+l.name, computeFrechet, l.lf)
		}
		for _, l := range locals {
			med := medianLocal(all, l.lf)
			for _, f := range []float64{0.1, 0.25, 0.5, 1} {
				eps := int64(f * float64(med))
				if eps < 1 {
					eps = 1
				}
				sf := func(x, y [][]int64, _ int64, d localdistfunc) (int64, [][]int) { return computeERD(x, y, eps, d) }
				row(fmt.Sprintf("ERD %s eps=%.2f med", l.name, f), sf, l.lf)
			}
		}
	}
}
