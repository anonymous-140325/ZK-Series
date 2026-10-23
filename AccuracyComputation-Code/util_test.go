package main

import (
	"fmt"
	"testing"
)

func TestFileNameFilter(t *testing.T) {
	fmt.Printf("Filter test: \n")

	dirName := "../ShakeDynamics-Dataset/Participant-1/"

	fileInfos1 := getFileInfos(dirName, "", "")
	fmt.Println(len(fileInfos1))

	fileInfos2 := getFileInfos(dirName, "", "xlsx")
	fmt.Println(len(fileInfos2))
}

func TestMotionData(t *testing.T) {
	// motionsense

	dirName := "./input/motionsense/A_DeviceMotion_data/dws_1/"

	fileNames := getFileNames(dirName, "", "")

	for _, fileInfo := range fileNames {
		fmt.Println(fileInfo) //print the files from directory
	}

	data1, _ := loadCsvDataRowFocus(fileNames[0], nil)
	data2, _ := loadCsvDataRowFocus(fileNames[1], nil)

	dtw, _ := computeDTW(data1, data2, 0, localDistanceEuclidean)
	fmt.Printf("DTW:\t\t %d\n", dtw)
}

func TestShakeAuthData(t *testing.T) {
	// motionsense

	dirName := "../ShakeDynamics-Dataset/Participant-1/"

	fileNames := getFileNames(dirName, "", "xlsx")

	for _, fileInfos := range fileNames {
		fmt.Println(fileInfos) //print the files from directory
	}

	sheetnames := []string{"Accelerometer"}

	data1, _ := loadXlsDataRowFocus(fileNames[0], []int{2, 3, 4}, sheetnames)
	data2, _ := loadXlsDataRowFocus(fileNames[1], []int{2, 3, 4}, sheetnames)

	dtw, _ := computeDTW(data1, data2, 0, localDistanceEuclidean)
	fmt.Printf("DTW:\t\t %d\n", dtw)
}

func TestNormalizeShakeAuthData(t *testing.T) {
	dirName := "../ShakeDynamics-Dataset/Participant-1/"

	fileNames := getFileNames(dirName, "", "xlsx")

	for _, fileInfos := range fileNames {
		fmt.Println(fileInfos) //print the files from directory
	}

	sheetnames := []string{"Accelerometer"}

	data1, _ := loadXlsDataRowFocus(fileNames[0], []int{2, 3, 4}, sheetnames)
	// data2, _ := loadXlsDataRowFocus(fileNames[1], []int{2, 3, 4},sheetnames)

	fmt.Println(data1)
	data1 = normalize(data1)
	fmt.Println(data1)
}
