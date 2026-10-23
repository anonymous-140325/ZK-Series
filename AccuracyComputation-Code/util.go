package main

import (
	"encoding/csv"
	"fmt"
	"io"
	"io/fs"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/xuri/excelize/v2"
)

const SCALE_FACTOR = float64(100000000)

func totSum(a, b []int64) int64 {
	d := int64(0)
	for i := range a {
		d += a[i] + b[i]
	}
	return d
}

func totDiag(a, b []int64) int64 {
	d := int64(0)
	for i := range a {
		d += int64(math.Sqrt(float64(a[i]*a[i] + b[i]*b[i])))
	}
	return d
}

func totEucl(a, b []int64) int64 {
	d := int64(0)
	for i := range a {
		d += (a[i] - b[i]) * (a[i] - b[i])
	}
	return d
}

func minInts(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func minIntFromArray(a []int64) int64 {
	result := a[0]
	for i := range a {
		result = minInts(a[i], result)
	}
	return result
}

// func maxInts(a, b int64) int64 {
// 	if a > b {
// 		return a
// 	}
// 	return b
// }

// func maxIntFromArray(a []int64) int64 {
// 	result := a[0]
// 	for i := range a {
// 		result = maxInts(a[i], result)
// 	}
// 	return result
// }

func kthLargestFromArray(a []int64, k int) int64 {
	b := make([]int64, len(a))
	copy(b, a)

	sort.Slice(b, func(i, j int) bool {
		return b[i] > b[j]
	})

	// fmt.Printf("%v\n", distsForSeries)

	return b[k]
}

func Check(e error) {
	if e != nil {
		panic(e)
	}
}

func loadCsvDataColumnFocus(filename string) ([][]int64, []string) {
	n := 0
	m := 0
	file, err := os.Open(filename)
	Check(err)
	r := csv.NewReader(file)
	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		} else {
			Check(err)
		}
		n += 1
		m = len(record)
	}

	result := make([][]int64, m)
	for i := range result {
		result[i] = make([]int64, n-1)
	}
	col_names := make([]string, m)

	count := -1
	file, err = os.Open(filename)
	Check(err)
	r = csv.NewReader(file)
	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		} else {
			Check(err)
		}

		if count >= 0 {
			for j := range record {
				record_float, _ := strconv.ParseFloat(record[j], 64)

				result[j][count] = int64(SCALE_FACTOR * record_float)
				Check(err)
			}
		} else {
			for j := range record {
				col_names[j] = record[j]
				Check(err)

			}
		}

		count += 1
	}

	return result, col_names
}

func loadCsvDataRowFocus(filename string, cols []int) ([][]int64, []string) {
	n := 0
	m := 0
	file, err := os.Open(filename)
	Check(err)
	r := csv.NewReader(file)

	// fmt.Println(len(cols))
	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		} else {
			Check(err)
		}
		n += 1
		m = len(record)
		if len(cols) > 0 {
			m = len(cols)
		}
	}

	result := make([][]int64, n-1)
	for i := range result {
		result[i] = make([]int64, m)
	}
	col_names := make([]string, m)

	count := -1
	file, err = os.Open(filename)
	Check(err)
	r = csv.NewReader(file)
	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		} else {
			Check(err)
		}

		if count >= 0 {
			for j := 0; j < m; j++ {
				colCell := record[j]
				if len(cols) > 0 {
					colCell = record[cols[j]]
				}
				record_float, _ := strconv.ParseFloat(strings.ReplaceAll(colCell, " ", ""), 64)
				result[count][j] = int64(SCALE_FACTOR * record_float)
				Check(err)

				// fmt.Printf("%v, %v\n", strings.ReplaceAll(colCell, " ", ""), record_float)
			}
		} else {
			for j := 0; j < m; j++ {
				colCell := record[j]
				if len(cols) > 0 {
					colCell = record[cols[j]]
				}
				col_names[j] = colCell
				Check(err)
			}
		}

		count += 1
	}

	return result, col_names
}

func loadXlsDataRowFocus(filename string, cols []int, sheetnames []string) ([][]int64, []string) {
	f, err := excelize.OpenFile(filename)
	if err != nil {
		fmt.Println(err)
		return nil, nil
	}

	defer func() {
		// Close the spreadsheet.
		if err := f.Close(); err != nil {
			fmt.Println(err)
		}
	}()

	c := len(sheetnames)
	m := len(cols)

	rows0, err := f.GetRows(sheetnames[0])
	if err != nil {
		fmt.Println(err)
		return nil, nil
	}

	n := len(rows0)

	result := make([][]int64, n-1)
	for i := range result {
		result[i] = make([]int64, m)
	}
	col_names := make([]string, m)

	cc := 0

	fmt.Println(cols)

	for _, sheetname := range sheetnames {

		rows, err := f.GetRows(sheetname)
		if err != nil {
			fmt.Println(err)
			return nil, nil
		}
		mm := len(cols) / c

		fmt.Println(mm)

		// fmt.Printf("n, m : %d, %d\n", n, m)

		count := -1
		for _, row := range rows {

			if count >= 0 {
				for j := 0; j < mm; j++ {
					colCell := row[cols[j+cc]]
					// fmt.Print(colCell, "\t")
					record_float, _ := strconv.ParseFloat(colCell, 64)
					result[count][j+cc] = int64(SCALE_FACTOR * record_float)
					Check(err)
				}
			} else {
				for j := 0; j < mm; j++ {
					colCell := row[cols[j+cc]]
					// fmt.Print(colCell, "\t")
					col_names[j+cc] = colCell
					Check(err)
				}
			}

			count += 1
		}
		cc += mm
	}

	fmt.Println(result)

	return result, col_names
}

func getFileNamesMultiDir(directories []string, prefix string, extension string) []string {
	fileNames := []string{}
	for _, directory := range directories {
		fileInfos := getFileInfos(directory, prefix, extension)

		for _, fileInfo := range fileInfos {
			fileNames = append(fileNames, directory+fileInfo.Name())
		}
	}

	return fileNames
}

func getFileNames(directory string, prefix string, extension string) []string {
	fileInfos := getFileInfos(directory, prefix, extension)
	fileNames := []string{}

	for _, fileInfo := range fileInfos {
		fileNames = append(fileNames, directory+fileInfo.Name())
	}

	return fileNames
}

func getFileInfos(directory string, prefix string, extension string) []fs.FileInfo {

	files, err := os.Open(directory) //open the directory to read files in the directory
	if err != nil {
		fmt.Println("error opening directory:", err) //print error if directory is not opened
		return nil
	}
	defer files.Close() //close the directory opened

	fileInfos, err := files.Readdir(-1) //read the files from the directory
	if err != nil {
		fmt.Println("error reading directory:", err) //if directory is not read properly print error message
		return nil
	}

	filteredFileInfos := []fs.FileInfo{}
	for _, fileInfo := range fileInfos {
		if len(prefix) == 0 || strings.HasPrefix(fileInfo.Name(), prefix) {
			if len(extension) == 0 || strings.HasSuffix(fileInfo.Name(), extension) {
				filteredFileInfos = append(filteredFileInfos, fileInfo)
			}
		}
	}

	return filteredFileInfos
}

func loadCsvDataFromFiles(fileNames []string, cols []int) [][][]int64 {
	data := make([][][]int64, len(fileNames))
	for i, fileName := range fileNames {
		csvData, _ := loadCsvDataRowFocus(fileName, cols)
		data[i] = csvData
	}
	return data
}

func loadXlsDataFromFiles(fileNames []string, cols []int, sheetnames []string) [][][]int64 {
	data := make([][][]int64, len(fileNames))
	for i, fileName := range fileNames {
		csvData, _ := loadXlsDataRowFocus(fileName, cols, sheetnames)
		data[i] = csvData
	}
	return data
}

func normalizeAll(data [][][]int64) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = normalize(data[i])
	}
	return result
}

func normalize2All(data [][][]int64) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = normalize2(data[i])
	}
	return result
}

func smoothAll(data [][][]int64, windowsize int, stepsize int) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = smooth(data[i], windowsize, stepsize)
	}
	return result
}

func differentiateAll(data [][][]int64, stepsize int) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = differentiate(data[i], stepsize)
	}
	return result
}

func startOnlyAll(data [][][]int64) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = startOnly(data[i])
	}
	return result
}

func nullShiftAll(data [][][]int64) [][][]int64 {
	result := make([][][]int64, len(data))
	for i := range data {
		result[i] = nullShift(data[i])
	}
	return result
}

func normalize(data [][]int64) [][]int64 {
	t := len(data)    // number of time points in the series
	d := len(data[0]) // dimension of the dataset
	result := make([][]int64, t)
	for i := range result {
		result[i] = make([]int64, d)
	}

	mins := make([]int64, d)
	sum := make([]int64, d)
	sum2 := make([]int64, d)

	for i := range data {
		for j := range data[i] {
			if mins[j] > data[i][j] {
				mins[j] = data[i][j]
			}
			sum[j] += data[i][j]
			sum2[j] += data[i][j] * data[i][j]
		}
	}
	means := make([]float64, d)
	sds := make([]float64, d)

	for j := 0; j < d; j++ {
		means[j] = float64(sum[j]) / float64(t)
		sds[j] = math.Sqrt(float64(sum2[j])/float64(t) - means[j]*means[j])
	}

	// fmt.Println(mins)
	// fmt.Println(means)
	// fmt.Println(sds)

	for i := range data {
		for j := range data[i] {
			result[i][j] = int64((float64(data[i][j]) - float64(mins[j])) * SCALE_FACTOR / sds[j])
		}
	}

	// fmt.Println(data)
	// fmt.Println(result)

	return result
}

func normalize2(data [][]int64) [][]int64 {
	t := len(data)    // number of time points in the series
	d := len(data[0]) // dimension of the dataset
	result := make([][]int64, t)
	for i := range result {
		result[i] = make([]int64, d)
	}

	min := int64(math.MaxInt64)
	max := int64(math.MinInt64)

	for i := range data {
		for j := range data[i] {
			if min > data[i][j] {
				min = data[i][j]
			}
			if max < data[i][j] {
				max = data[i][j]
			}
		}
	}

	fmt.Println(min, max)

	for i := range data {
		for j := range data[i] {
			result[i][j] = 1000000 * (data[i][j] - min) / (max - min)
		}
	}

	return result
}

func smooth(data [][]int64, windowsize int, stepsize int) [][]int64 {
	t := len(data) / stepsize // number of time points in the series
	d := len(data[0])         // dimension of the dataset
	result := make([][]int64, t)
	for i := range result {
		result[i] = make([]int64, d)
	}

	for i := range result {
		for j := range data[i] {
			total := int64(0)
			n := int64(0)
			for k := 0; k < windowsize; k++ {
				if stepsize*i+k < len(data) {
					total += data[stepsize*i+k][j]
					n++
				}
			}
			result[i][j] = total / n
		}
	}

	return result
}

func differentiate(data [][]int64, stepsize int) [][]int64 {
	t := len(data) - stepsize // number of time points in the series
	d := len(data[0])         // dimension of the dataset
	result := make([][]int64, t)
	for i := range result {
		result[i] = make([]int64, d)
	}

	for i := range result {
		for j := range data[i] {
			result[i][j] = data[i+stepsize][j] - data[i][j]
		}
	}

	return result
}

func startOnly(data [][]int64) [][]int64 {
	d := len(data[0]) // dimension of the dataset
	result := make([][]int64, 2)
	for i := range result {
		result[i] = make([]int64, d)
	}

	for i := range result {
		copy(result[i], data[i])
	}

	return result
}

func nullShift(data [][]int64) [][]int64 {
	t := len(data)    // number of time points in the series
	d := len(data[0]) // dimension of the dataset
	result := make([][]int64, t)
	for i := range result {
		result[i] = make([]int64, d)
	}

	for i := range result {
		for j := range data[i] {
			result[i][j] = data[i][j] - data[0][j]
		}
	}

	return result
}
