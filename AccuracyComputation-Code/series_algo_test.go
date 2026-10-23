package main

import (
	"fmt"
	"testing"
)

func TestCsvLoader(t *testing.T) {
	data1, colnames1 := loadCsvDataColumnFocus("input/A_DeviceMotion_data/dws_1/sub_1.csv")

	fmt.Printf("num rows: %d\n", len(data1[0]))
	fmt.Printf("num columns: %d\n", len(colnames1))

	data2, colnames2 := loadCsvDataRowFocus("input/A_DeviceMotion_data/dws_1/sub_1.csv", nil)

	fmt.Printf("num rows: %d\n", len(data2[0]))
	fmt.Printf("num columns: %d\n", len(colnames2))
}

func TestAllActivityData(t *testing.T) {
	lambda := int64(1000000000000)
	data1, data2, data3, data1b, data2b, data3b := loadActivityData()
	fmt.Printf("--- DTW: \n")
	compareSeries(data1, data2, data3, data1b, data2b, data3b, computeDTW, localDistanceManhattan, 0, "DTW")
	fmt.Printf("\n--- EDR: \n")
	compareSeries(data1, data2, data3, data1b, data2b, data3b, computeERD, localDistanceManhattan, lambda, "ERD")
	fmt.Printf("\n--- TWED: \n")
	compareSeries(data1, data2, data3, data1b, data2b, data3b, computeTWED, localDistanceManhattan, lambda, "TWED")
}

func loadActivityData() ([][]int64, [][]int64, [][]int64, [][]int64, [][]int64, [][]int64) {
	data1, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/dws_1/sub_1.csv", nil)
	data2, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/dws_1/sub_2.csv", nil)
	data3, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/dws_1/sub_3.csv", nil)

	data1b, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/jog_9/sub_1.csv", nil)
	data2b, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/jog_9/sub_2.csv", nil)
	data3b, _ := loadCsvDataRowFocus("input/motionsense/A_DeviceMotion_data/jog_9/sub_3.csv", nil)

	return data1, data2, data3, data1b, data2b, data3b
}

func compareSeries(data1 [][]int64, data2 [][]int64, data3 [][]int64, data1b [][]int64, data2b [][]int64, data3b [][]int64, computeSeriesDist seriesdistfunc, dist localdistfunc, lambda int64, name string) {

	dtw12, _ := computeSeriesDist(data1, data2, lambda, dist)
	dtw13, _ := computeSeriesDist(data1, data3, lambda, dist)
	dtw23, _ := computeSeriesDist(data2, data3, lambda, dist)

	fmt.Printf("%s 1 v. 2:\t\t %d\n", name, dtw12)
	fmt.Printf("%s 1 v. 3:\t\t %d\n", name, dtw13)
	fmt.Printf("%s 2 v. 3:\t\t %d\n", name, dtw23)

	dtw1b2b, _ := computeSeriesDist(data1b, data2b, lambda, dist)
	dtw1b3b, _ := computeSeriesDist(data1b, data3b, lambda, dist)
	dtw2b3b, _ := computeSeriesDist(data2b, data3b, lambda, dist)

	fmt.Printf("%s 1b v. 2b:\t\t %d\n", name, dtw1b2b)
	fmt.Printf("%s 1b v. 3b:\t\t %d\n", name, dtw1b3b)
	fmt.Printf("%s 2b v. 3b:\t\t %d\n", name, dtw2b3b)

	dtw11b, _ := computeSeriesDist(data1, data1b, lambda, dist)
	dtw22b, _ := computeSeriesDist(data2, data2b, lambda, dist)
	dtw33b, _ := computeSeriesDist(data3, data3b, lambda, dist)

	fmt.Printf("%s 1 v. 1b:\t\t %d\n", name, dtw11b)
	fmt.Printf("%s 2 v. 2b:\t\t %d\n", name, dtw22b)
	fmt.Printf("%s 3 v. 3b:\t\t %d\n", name, dtw33b)
}
