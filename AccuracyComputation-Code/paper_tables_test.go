package main

// Regenerates the accuracy tables of the paper (Section 6 and Appendix D): for each case study,
// every series distance (sum, DTW, TWED) x local distance (d_1, d_2, d_inf), FAR and FRR for
// q = 0, 1, 2, with k = 1 and k = 3. Results are written to paper-tables.txt.
//
//   go test -run TestPaperTables -timeout 180m .
//
// One case study at a time: -run 'TestPaperTables/blow' (or shake, face).

import (
	"fmt"
	"os"
	"strconv"
	"testing"
)

func TestPaperTables(t *testing.T) {
	out, err := os.Create("paper-tables.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()

	type combo struct {
		series, local string
		sf            seriesdistfunc
		lf            localdistfunc
	}
	combos := []combo{
		{"sum", "d1", computeDiagSum, localDistanceManhattan},
		{"sum", "d2", computeDiagSum, localDistanceEuclidean},
		{"sum", "dinf", computeDiagSum, localDistanceChebyshev},
		{"DTW", "d1", computeDTW, localDistanceManhattan},
		{"DTW", "d2", computeDTW, localDistanceEuclidean},
		{"DTW", "dinf", computeDTW, localDistanceChebyshev},
		{"TWED", "d1", computeTWED, localDistanceManhattan},
		{"TWED", "d2", computeTWED, localDistanceEuclidean},
		{"TWED", "dinf", computeTWED, localDistanceChebyshev},
	}
	studies := []struct {
		name string
		run  func(seriesdistfunc, localdistfunc, int, int) (float64, float64, float64, float64, float64, float64)
	}{
		{"blow", func(s seriesdistfunc, l localdistfunc, p, k int) (float64, float64, float64, float64, float64, float64) {
			return experiment1blowauth(s, l, []int{1}, p, k)
		}},
		{"shake", func(s seriesdistfunc, l localdistfunc, p, k int) (float64, float64, float64, float64, float64, float64) {
			return experiment1shakeauth(s, l, []string{"Orientation"}, []int{2, 5, 8}, p, k)
		}},
		{"face", func(s seriesdistfunc, l localdistfunc, p, k int) (float64, float64, float64, float64, float64, float64) {
			return experiment1faceauth(s, l, get2dPoints(append([]int{}, faceLandmarks...)), p, k)
		}},
	}
	for _, st := range studies {
		st := st
		t.Run(st.name, func(t *testing.T) {
			for _, k := range []int{1, 3} {
				fmt.Fprintf(out, "%s, k = %d (FAR / FRR)\n", st.name, k)
				for _, c := range combos {
					line := fmt.Sprintf("  %-4s %-4s", c.series, c.local)
					for p := 0; p <= 2; p++ {
						_, fp, _, fn, totP, totN := st.run(c.sf, c.lf, p, k)
						line += fmt.Sprintf("   q=%d %.4f / %.4f", p, fp/totN, fn/totP)
					}
					fmt.Fprintln(out, line)
				}
				out.Sync()
			}
		})
	}
	_ = strconv.Itoa
}
