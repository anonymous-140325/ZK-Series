package main

import (
	"fmt"
	"sort"
	"strconv"
	"testing"
	"time"
)

func TestSmoother(t *testing.T) {
	data1a := []int64{1, 21, 110}
	data1b := []int64{3, 12, 120}
	data1c := []int64{7, 22, 130}
	data1d := []int64{3, 23, 140}
	data1e := []int64{6, 23, 150}
	data1f := []int64{9, 22, 160}
	data1g := []int64{1, 21, 170}
	data1h := []int64{2, 32, 180}
	data1i := []int64{3, 12, 190}
	data1j := []int64{6, 22, 200}

	data1 := make([][]int64, 10)
	// data1 := [][]int64{}
	data1[0] = data1a
	data1[1] = data1b
	data1[2] = data1c
	data1[3] = data1d
	data1[4] = data1e
	data1[5] = data1f
	data1[6] = data1g
	data1[7] = data1h
	data1[8] = data1i
	data1[9] = data1j

	data2 := smooth(data1, 8, 4)
	fmt.Printf("%v\n", data1)
	fmt.Printf("%v\n", data2)
}

func determineThresholds(data [][][][]int64, k int, p int, seriesfunc seriesdistfunc, localfunc localdistfunc) []int64 {
	thresholds := make([]int64, len(data))

	for u, dataGroup := range data {
		// for each user

		dists := make([][]int64, len(dataGroup))
		for j := range dataGroup {
			dists[j] = make([]int64, len(dataGroup))
		}

		for i := range dataGroup {
			for j := range dataGroup {
				dists[i][j], _ = seriesfunc(dataGroup[i], dataGroup[j], 1000000, localfunc)
				// fmt.Printf("u,i,j,n: %d, %d, %d, %d, %v\n", u, i, j, len(dataGroup), dataGroup[i])
			}
		}

		thresholdCandidates := make([]int64, len(dataGroup))
		for i := range dataGroup {
			distsForSeries := make([]int64, len(dataGroup))
			for j := range dataGroup {
				distsForSeries[j] = dists[i][j]
			}

			sort.Slice(distsForSeries, func(i, j int) bool {
				return distsForSeries[i] < distsForSeries[j]
			})

			// fmt.Printf("%v\n", distsForSeries)

			thresholdCandidates[i] = distsForSeries[k] // k: -1 because arrays start at 0, +1 because the distance between the series and itself is included
		}

		// fmt.Printf("%v\n", thresholdCandidates)

		thresholds[u] = kthLargestFromArray(thresholdCandidates, p)
	}

	return thresholds
}

// compareData returns the fraction of the new readings that are accepted against the base
// readings with the given threshold, i.e. whose k-th nearest base reading is within it. If the
// new readings are the base readings themselves (genuine attempts), the distance of a reading
// to itself is skipped.
func compareData(new [][][]int64, base [][][]int64, threshold int64, k int, selfIncluded bool, seriesfunc seriesdistfunc, localfunc localdistfunc) float64 {
	tDists := make([]int64, len(new))
	dists := make([][]int64, len(new))
	for j := range new {
		dists[j] = make([]int64, len(base))
	}

	nCorrect := 0

	// fmt.Printf("%v, %v\n", threshold, k)

	for i := range new {
		for j := range base {
			dists[i][j], _ = seriesfunc(new[i], base[j], 1000000, localfunc)
		}

		distsForSeries := make([]int64, len(base))
		for j := range base {
			distsForSeries[j] = dists[i][j]
		}

		sort.Slice(distsForSeries, func(i, j int) bool {
			return distsForSeries[i] < distsForSeries[j]
		})

		// fmt.Printf("%v\n", distsForSeries)

		idx := k - 1 // the k-th nearest base reading
		if selfIncluded {
			idx = k // skip the distance of the reading to itself
		}
		tDists[i] = distsForSeries[idx]
		// fmt.Printf("%v vs %v, %v\n", tDists[i], threshold, tDists[i] <= threshold)
		if tDists[i] <= threshold {
			nCorrect += 1
		}
	}

	return float64(nCorrect) / float64(len(new))
}

func TestExperiment1Motion(t *testing.T) {
	allData := make([][][][]int64, 2)

	dwsDirs := []string{"./input/motionsense/A_DeviceMotion_data/dws_1/", "./input/motionsense/A_DeviceMotion_data/dws_2/", "./input/motionsense/A_DeviceMotion_data/dws_11/"}
	fileNames := getFileNamesMultiDir(dwsDirs, "", "")
	allData[0] = loadCsvDataFromFiles(fileNames, nil)

	dwsDirs = []string{"./input/motionsense/A_DeviceMotion_data/jog_9/", "./input/motionsense/A_DeviceMotion_data/jog_16/"}
	fileNames = getFileNamesMultiDir(dwsDirs, "", "")
	allData[1] = loadCsvDataFromFiles(fileNames, nil)

	thresholds := determineThresholds(allData, 3, 0, computeDiagSum, localDistanceManhattan)
	// fmt.Printf("%v\n", thresholds)

	for i := range allData {
		for j := range allData {
			correctFrac := compareData(allData[j], allData[i], thresholds[i], 8, i == j, computeDiagSum, localDistanceManhattan)

			fmt.Printf("Motion %d vs Motion %d: %f\n", i+1, j+1, correctFrac)
		}
	}
}

func processExperiment1(allData [][][][]int64, p int, seriesfunc seriesdistfunc, localfunc localdistfunc, k int, names []string) (float64, float64, float64, float64, float64, float64) {
	thresholds := determineThresholds(allData, k, p, seriesfunc, localfunc)
	// fmt.Printf("%v\n", thresholds)

	truePositive := 0.0
	falsePositive := 0.0
	trueNegative := 0.0
	falseNegative := 0.0
	totalPositive := 0.0
	totalNegative := 0.0

	for i := range allData {
		for j := range allData {
			// user j's readings as attempts against user i's base readings, with user i's threshold
			matchFrac := compareData(allData[j], allData[i], thresholds[i], k, i == j, seriesfunc, localfunc)

			if i == j {
				truePositive += matchFrac
				falseNegative += 1 - matchFrac
				totalPositive += 1
			} else {
				falsePositive += matchFrac
				trueNegative += 1 - matchFrac
				totalNegative += 1
			}

			fmt.Printf("User %v vs User %v: %f\n", names[i], names[j], matchFrac)
		}
	}

	return truePositive, falsePositive, trueNegative, falseNegative, totalPositive, totalNegative
}

func experiment1shakeauth(seriesfunc seriesdistfunc, localfunc localdistfunc, sheetnames []string, cols []int, p int, k int) (float64, float64, float64, float64, float64, float64) {
	names := make([]string, 20)
	for i := range names {
		names[i] = "User" + strconv.Itoa(i+1)
	}

	allData := make([][][][]int64, len(names))

	for i := range allData {
		allData[i] = loadXlsDataFromFiles(getFileNames("../ShakeDynamics-Dataset/Participant-"+strconv.Itoa(i+1)+"/", "", "xlsx"), cols, sheetnames)
		// allData[i] = normalizeAll(allData[i])
	}

	return processExperiment1(allData, p, seriesfunc, localfunc, k, names)
}

func experiment1blowauth(seriesfunc seriesdistfunc, localfunc localdistfunc, cols []int, p int, k int) (float64, float64, float64, float64, float64, float64) {
	names := participantNames(50)
	allData := make([][][][]int64, len(names))

	for i, s := range names {
		allData[i] = loadCsvDataFromFiles(getFileNames("../BlowAcoustics-Dataset/"+s+"/", "", "csv"), cols)
		allData[i] = normalizeAll(allData[i])
		allData[i] = smoothAll(allData[i], 8, 4)

	}

	return processExperiment1(allData, p, seriesfunc, localfunc, k, names)
}

func experiment1faceauth(seriesfunc seriesdistfunc, localfunc localdistfunc, cols []int, p int, k int) (float64, float64, float64, float64, float64, float64) {
	names := participantNames(20)
	allData := make([][][][]int64, len(names))

	for i, s := range names {
		allData[i] = loadCsvDataFromFiles(getFileNames("../FacialLandmark-Dataset/"+s+"/", "", "csv"), cols)
		allData[i] = nullShiftAll(allData[i])
		// allData[i] = differentiateAll(allData[i], 1)
		// allData[i] = startOnlyAll(allData[i])
		// allData[i] = normalize2All(allData[i])
		// allData[i] = smoothAll(allData[i], 8, 4)

	}

	return processExperiment1(allData, p, seriesfunc, localfunc, k, names)
}

func TestExperiment1ShakeAuthSingle(t *testing.T) {
	// seriesfunc := computeDiagSum
	// localfunc := localDistanceManhattan

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceChebyshev

	seriesfunc := computeDTW
	localfunc := localDistanceManhattan

	// seriesfunc := computeDTW
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeDTW
	// localfunc := localDistanceChebyshev

	// seriesfunc := computeTWED
	// localfunc := localDistanceManhattan

	// seriesfunc := computeTWED
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeTWED
	// localfunc := localDistanceChebyshev

	// sheetnames := []string{"Orientation", "Accelerometer"}
	// cols := []int{2, 5, 8, 2, 3, 4}

	sheetnames := []string{"Orientation"}
	cols := []int{2, 5, 8}

	// sheetnames := []string{"Accelerometer"}
	// cols := []int{2, 3, 4}

	// sheetnames := []string{"Gyroscope"}
	// cols := []int{2, 3, 4}

	// sheetnames := []string{"Magnetometer"}
	// cols := []int{2, 3, 4}

	// sheetnames := []string{"Gyroscope", "Accelerometer"}
	// cols := []int{2, 3, 4, 2, 3, 4}

	p := 2
	k := 1

	truePositive, falsePositive, trueNegative, falseNegative, totalPositive, totalNegative := experiment1shakeauth(seriesfunc, localfunc, sheetnames, cols, p, k)

	fmt.Printf("true positive (precision): %f\n", truePositive/totalPositive)
	fmt.Printf("true negative (recall): %f\n", trueNegative/totalNegative)
	fmt.Printf("false positive: %f\n", falsePositive/totalNegative)
	fmt.Printf("false negative: %f\n", falseNegative/totalPositive)
	fmt.Printf("accuracy: %f\n", (truePositive+trueNegative)/(totalPositive+totalNegative))
}

func TestExperiment1BlowAuthSingle(t *testing.T) {
	seriesfunc := computeDiagSum
	localfunc := localDistanceManhattan

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceChebyshev

	// seriesfunc := computeDTW
	// localfunc := localDistanceManhattan

	// seriesfunc := computeDTW
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeDTW
	// localfunc := localDistanceChebyshev

	// seriesfunc := computeTWED
	// localfunc := localDistanceManhattan

	// seriesfunc := computeTWED
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeTWED
	// localfunc := localDistanceChebyshev

	p := 2
	k := 1

	truePositive, falsePositive, trueNegative, falseNegative, totalPositive, totalNegative := experiment1blowauth(seriesfunc, localfunc, []int{1}, p, k)

	fmt.Printf("true positive (precision): %f\n", truePositive/totalPositive)
	fmt.Printf("true negative (recall): %f\n", trueNegative/totalNegative)
	fmt.Printf("false positive: %f\n", falsePositive/totalNegative)
	fmt.Printf("false negative: %f\n", falseNegative/totalPositive)
	fmt.Printf("accuracy: %f\n", (truePositive+trueNegative)/(totalPositive+totalNegative))
}

func TestExperiment1FaceAuthSingle(t *testing.T) {
	// seriesfunc := computeDiagSum
	// localfunc := localDistanceManhattan

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeDiagSum
	// localfunc := localDistanceChebyshev

	// seriesfunc := computeDTW
	// localfunc := localDistanceManhattan

	// seriesfunc := computeDTW
	// localfunc := localDistanceEuclidean

	seriesfunc := computeDTW
	localfunc := localDistanceChebyshev

	// seriesfunc := computeTWED
	// localfunc := localDistanceManhattan

	// seriesfunc := computeTWED
	// localfunc := localDistanceEuclidean

	// seriesfunc := computeTWED
	// localfunc := localDistanceChebyshev

	// dims := []int{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 419, 420, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 433, 434, 435, 436, 437, 438, 439, 440, 441, 442, 443, 444, 445, 446, 447, 448, 449, 450, 451, 452, 453, 454, 455, 456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477, 478, 479, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511, 512, 513, 514, 515, 516, 517, 518, 519, 520, 521, 522, 523, 524, 525, 526, 527, 528, 529, 530, 531, 532, 533, 534, 535, 536, 537, 538, 539, 540, 541, 542, 543, 544, 545, 546, 547, 548, 549, 550, 551, 552, 553, 554, 555, 556, 557, 558, 559, 560, 561, 562, 563, 564, 565, 566, 567, 568, 569, 570, 571, 572, 573, 574, 575, 576, 577, 578, 579, 580, 581, 582, 583, 584, 585, 586, 587, 588, 589, 590, 591, 592, 593, 594, 595, 596, 597, 598, 599, 600, 601, 602, 603, 604, 605, 606, 607, 608, 609, 610, 611, 612, 613, 614, 615, 616, 617, 618, 619, 620, 621, 622, 623, 624, 625, 626, 627, 628, 629, 630, 631, 632, 633, 634, 635, 636, 637, 638, 639, 640, 641, 642, 643, 644, 645, 646, 647, 648, 649, 650, 651, 652, 653, 654, 655, 656, 657, 658, 659, 660, 661, 662, 663, 664, 665, 666, 667, 668, 669, 670, 671, 672, 673, 674, 675, 676, 677, 678, 679, 680, 681, 682, 683, 684, 685, 686, 687, 688, 689, 690, 691, 692, 693, 694, 695, 696, 697, 698, 699, 700, 701, 702, 703, 704, 705, 706, 707, 708, 709, 710, 711, 712, 713, 714, 715, 716, 717, 718, 719, 720, 721, 722, 723, 724, 725, 726, 727, 728, 729, 730, 731, 732, 733, 734, 735, 736, 737, 738, 739, 740, 741, 742, 743, 744, 745, 746, 747, 748, 749, 750, 751, 752, 753, 754, 755, 756, 757, 758, 759, 760, 761, 762, 763, 764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777, 778, 779, 780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 793, 794, 795, 796, 797, 798, 799, 800, 801, 802, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817, 818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828, 829, 830, 831, 832, 833, 834, 835, 836, 837, 838, 839, 840, 841, 842, 843, 844, 845, 846, 847, 848, 849, 850, 851, 852, 853, 854, 855, 856, 857, 858, 859, 860, 861, 862, 863, 864, 865, 866, 867, 868, 869, 870, 871, 872, 873, 874, 875, 876, 877, 878, 879, 880, 881, 882, 883, 884, 885, 886, 887, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 899, 900, 901, 902, 903, 904, 905, 906, 907, 908, 909, 910, 911, 912, 913, 914, 915, 916, 917, 918, 919, 920, 921, 922, 923, 924, 925, 926, 927, 928, 929, 930, 931, 932, 933, 934, 935, 936, 937, 938, 939, 940, 941, 942, 943, 944, 945, 946, 947, 948, 949, 950, 951, 952, 953, 954, 955, 956, 957, 958, 959, 960, 961, 962, 963, 964, 965, 966, 967, 968, 969, 970, 971, 972, 973, 974, 975, 976, 977, 978, 979, 980, 981, 982, 983, 984, 985, 986, 987, 988, 989, 990, 991, 992, 993, 994, 995, 996, 997, 998, 999, 1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037, 1038, 1039, 1040, 1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078, 1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093, 1094, 1095, 1096, 1097, 1098, 1099, 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127, 1128, 1129, 1130, 1131, 1132, 1133, 1134, 1135, 1136, 1137, 1138, 1139, 1140, 1141, 1142, 1143, 1144, 1145, 1146, 1147, 1148, 1149, 1150, 1151, 1152, 1153, 1154, 1155, 1156, 1157, 1158, 1159, 1160, 1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168, 1169, 1170, 1171, 1172, 1173, 1174, 1175, 1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211, 1212, 1213, 1214, 1215, 1216, 1217, 1218, 1219, 1220, 1221, 1222, 1223, 1224, 1225, 1226, 1227, 1228, 1229, 1230, 1231, 1232, 1233, 1234, 1235, 1236, 1237, 1238, 1239, 1240, 1241, 1242, 1243, 1244, 1245, 1246, 1247, 1248, 1249, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 1259, 1260, 1261, 1262, 1263, 1264, 1265, 1266, 1267, 1268, 1269, 1270, 1271, 1272, 1273, 1274, 1275, 1276, 1277, 1278, 1279, 1280, 1281, 1282, 1283, 1284, 1285, 1286, 1287, 1288, 1289, 1290, 1291, 1292, 1293, 1294, 1295, 1296, 1297, 1298, 1299, 1300, 1301, 1302, 1303, 1304, 1305, 1306, 1307, 1308, 1309, 1310, 1311, 1312, 1313, 1314, 1315, 1316, 1317, 1318, 1319, 1320, 1321, 1322, 1323, 1324, 1325, 1326, 1327, 1328, 1329, 1330, 1331, 1332, 1333, 1334, 1335, 1336, 1337, 1338, 1339, 1340, 1341, 1342, 1343, 1344, 1345, 1346, 1347, 1348, 1349, 1350, 1351, 1352, 1353, 1354, 1355, 1356, 1357, 1358, 1359, 1360, 1361, 1362, 1363, 1364, 1365, 1366, 1367, 1368, 1369, 1370, 1371, 1372, 1373, 1374, 1375, 1376, 1377, 1378, 1379, 1380, 1381, 1382, 1383, 1384, 1385, 1386, 1387, 1388, 1389, 1390, 1391, 1392, 1393, 1394, 1395, 1396, 1397, 1398, 1399, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407, 1408, 1409, 1410, 1411, 1412, 1413, 1414, 1415, 1416, 1417, 1418, 1419, 1420, 1421, 1422, 1423, 1424, 1425, 1426, 1427, 1428, 1429, 1430, 1431, 1432, 1433}
	// dims := []int{138, 139, 140, 165, 166, 167, 558, 559, 560, 828, 829, 830, 855, 856, 857, 1230, 1231, 1232}

	dims := get2dPoints(append([]int{}, faceLandmarks...))

	p := 2
	k := 1

	truePositive, falsePositive, trueNegative, falseNegative, totalPositive, totalNegative := experiment1faceauth(seriesfunc, localfunc, dims, p, k)

	fmt.Printf("true positive (precision): %f\n", truePositive/totalPositive)
	fmt.Printf("true negative (recall): %f\n", trueNegative/totalNegative)
	fmt.Printf("false positive: %f\n", falsePositive/totalNegative)
	fmt.Printf("false negative: %f\n", falseNegative/totalPositive)
	fmt.Printf("accuracy: %f\n", (truePositive+trueNegative)/(totalPositive+totalNegative))
}

func TestTimeSeriesLengthDistribution(t *testing.T) {
	allData := make([][][][]int64, 20)
	sheetnames := []string{"Orientation"}
	cols := []int{2, 5, 8}

	lengths := []int{}
	totLength := 0

	for i := range allData {
		allData[i] = loadXlsDataFromFiles(getFileNames("../ShakeDynamics-Dataset/Participant-"+strconv.Itoa(i+1)+"/", "", "xlsx"), cols, sheetnames)

		for j := range allData[i] {
			// fmt.Printf("Time Series length: %d\n", len(allData[i][j]))
			lengths = append(lengths, len(allData[i][j]))
			totLength += len(allData[i][j])
		}
	}

	sort.Slice(lengths, func(i, j int) bool {
		return lengths[i] < lengths[j]
	})

	fmt.Println(lengths)

	fmt.Printf("median: %d\n", lengths[len(lengths)/2-1])
	fmt.Printf("min: %d\n", lengths[0])
	fmt.Printf("max: %d\n", lengths[len(lengths)-1])
	fmt.Printf("average: %f\n", float64(totLength)/float64(len(lengths)))
}

func TestExperiment1ShakeAuthAll(t *testing.T) {
	// sheetname := "Orientation"
	// cols := []int{2, 5, 8}
	// p := 2
	// k := 1

	sheetnames := []string{"Gyroscope", "Accelerometer"}
	cols := []int{2, 3, 4, 2, 3, 4}

	// sheetnames := []string{"Orientation"}
	// cols := []int{2, 5, 8}

	p := 2
	k := 1

	truePositive1, falsePositive1, trueNegative1, falseNegative1, totalPositive1, totalNegative1 := experiment1shakeauth(computeDiagSum, localDistanceManhattan, sheetnames, cols, p, k)
	truePositive2, falsePositive2, trueNegative2, falseNegative2, totalPositive2, totalNegative2 := experiment1shakeauth(computeDiagSum, localDistanceEuclidean, sheetnames, cols, p, k)
	truePositive3, falsePositive3, trueNegative3, falseNegative3, totalPositive3, totalNegative3 := experiment1shakeauth(computeDiagSum, localDistanceChebyshev, sheetnames, cols, p, k)
	truePositive4, falsePositive4, trueNegative4, falseNegative4, totalPositive4, totalNegative4 := experiment1shakeauth(computeDTW, localDistanceManhattan, sheetnames, cols, p, k)
	truePositive5, falsePositive5, trueNegative5, falseNegative5, totalPositive5, totalNegative5 := experiment1shakeauth(computeDTW, localDistanceEuclidean, sheetnames, cols, p, k)
	truePositive6, falsePositive6, trueNegative6, falseNegative6, totalPositive6, totalNegative6 := experiment1shakeauth(computeDTW, localDistanceChebyshev, sheetnames, cols, p, k)
	truePositive7, falsePositive7, trueNegative7, falseNegative7, totalPositive7, totalNegative7 := experiment1shakeauth(computeTWED, localDistanceManhattan, sheetnames, cols, p, k)
	truePositive8, falsePositive8, trueNegative8, falseNegative8, totalPositive8, totalNegative8 := experiment1shakeauth(computeTWED, localDistanceEuclidean, sheetnames, cols, p, k)
	truePositive9, falsePositive9, trueNegative9, falseNegative9, totalPositive9, totalNegative9 := experiment1shakeauth(computeTWED, localDistanceChebyshev, sheetnames, cols, p, k)

	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive1+trueNegative1)/(totalPositive1+totalNegative1), (falsePositive1 / (falsePositive1 + trueNegative1)), (truePositive1 / (truePositive1 + falsePositive1)), (truePositive1 / (truePositive1 + falseNegative1)))
	fmt.Printf("        sum & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive2+trueNegative2)/(totalPositive2+totalNegative2), (falsePositive2 / (falsePositive2 + trueNegative2)), (truePositive2 / (truePositive2 + falsePositive2)), (truePositive2 / (truePositive2 + falseNegative2)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive3+trueNegative3)/(totalPositive3+totalNegative3), (falsePositive3 / (falsePositive3 + trueNegative3)), (truePositive3 / (totalPositive3 + falsePositive3)), (truePositive3 / (truePositive3 + falseNegative3)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive4+trueNegative4)/(totalPositive4+totalNegative4), (falsePositive4 / (falsePositive4 + trueNegative4)), (truePositive4 / (truePositive4 + falsePositive4)), (truePositive4 / (truePositive4 + falseNegative4)))
	fmt.Printf("        DTW & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive5+trueNegative5)/(totalPositive5+totalNegative5), (falsePositive5 / (falsePositive5 + trueNegative5)), (truePositive5 / (truePositive5 + falsePositive5)), (truePositive5 / (truePositive5 + falseNegative5)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive6+trueNegative6)/(totalPositive6+totalNegative6), (falsePositive6 / (falsePositive6 + trueNegative6)), (truePositive6 / (totalPositive6 + falsePositive6)), (truePositive6 / (truePositive6 + falseNegative6)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive7+trueNegative7)/(totalPositive7+totalNegative7), (falsePositive7 / (falsePositive7 + trueNegative7)), (truePositive7 / (truePositive7 + falsePositive7)), (truePositive7 / (truePositive7 + falseNegative7)))
	fmt.Printf("        TWED & $d_{2}$   & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive8+trueNegative8)/(totalPositive8+totalNegative8), (falsePositive8 / (falsePositive8 + trueNegative8)), (truePositive8 / (truePositive8 + falsePositive8)), (truePositive8 / (truePositive8 + falseNegative8)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\bottomrule\n", (truePositive9+trueNegative9)/(totalPositive9+totalNegative9), (falsePositive9 / (falsePositive9 + trueNegative9)), (truePositive9 / (totalPositive9 + falsePositive9)), (truePositive9 / (truePositive9 + falseNegative9)))
}

func TestExperiment1BlowAuthAll(t *testing.T) {
	// sheetname := "Orientation"
	// cols := []int{2, 5, 8}
	// p := 2
	// k := 1

	cols := []int{1}
	p := 0
	k := 1

	truePositive1, falsePositive1, trueNegative1, falseNegative1, totalPositive1, totalNegative1 := experiment1blowauth(computeDiagSum, localDistanceManhattan, cols, p, k)
	truePositive2, falsePositive2, trueNegative2, falseNegative2, totalPositive2, totalNegative2 := experiment1blowauth(computeDiagSum, localDistanceEuclidean, cols, p, k)
	truePositive3, falsePositive3, trueNegative3, falseNegative3, totalPositive3, totalNegative3 := experiment1blowauth(computeDiagSum, localDistanceChebyshev, cols, p, k)
	truePositive4, falsePositive4, trueNegative4, falseNegative4, totalPositive4, totalNegative4 := experiment1blowauth(computeDTW, localDistanceManhattan, cols, p, k)
	truePositive5, falsePositive5, trueNegative5, falseNegative5, totalPositive5, totalNegative5 := experiment1blowauth(computeDTW, localDistanceEuclidean, cols, p, k)
	truePositive6, falsePositive6, trueNegative6, falseNegative6, totalPositive6, totalNegative6 := experiment1blowauth(computeDTW, localDistanceChebyshev, cols, p, k)
	truePositive7, falsePositive7, trueNegative7, falseNegative7, totalPositive7, totalNegative7 := experiment1blowauth(computeTWED, localDistanceManhattan, cols, p, k)
	truePositive8, falsePositive8, trueNegative8, falseNegative8, totalPositive8, totalNegative8 := experiment1blowauth(computeTWED, localDistanceEuclidean, cols, p, k)
	truePositive9, falsePositive9, trueNegative9, falseNegative9, totalPositive9, totalNegative9 := experiment1blowauth(computeTWED, localDistanceChebyshev, cols, p, k)

	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive1+trueNegative1)/(totalPositive1+totalNegative1), (falsePositive1 / (falsePositive1 + trueNegative1)), (truePositive1 / (truePositive1 + falsePositive1)), (truePositive1 / (truePositive1 + falseNegative1)))
	fmt.Printf("        sum & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive2+trueNegative2)/(totalPositive2+totalNegative2), (falsePositive2 / (falsePositive2 + trueNegative2)), (truePositive2 / (truePositive2 + falsePositive2)), (truePositive2 / (truePositive2 + falseNegative2)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive3+trueNegative3)/(totalPositive3+totalNegative3), (falsePositive3 / (falsePositive3 + trueNegative3)), (truePositive3 / (totalPositive3 + falsePositive3)), (truePositive3 / (truePositive3 + falseNegative3)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive4+trueNegative4)/(totalPositive4+totalNegative4), (falsePositive4 / (falsePositive4 + trueNegative4)), (truePositive4 / (truePositive4 + falsePositive4)), (truePositive4 / (truePositive4 + falseNegative4)))
	fmt.Printf("        DTW & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive5+trueNegative5)/(totalPositive5+totalNegative5), (falsePositive5 / (falsePositive5 + trueNegative5)), (truePositive5 / (truePositive5 + falsePositive5)), (truePositive5 / (truePositive5 + falseNegative5)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive6+trueNegative6)/(totalPositive6+totalNegative6), (falsePositive6 / (falsePositive6 + trueNegative6)), (truePositive6 / (totalPositive6 + falsePositive6)), (truePositive6 / (truePositive6 + falseNegative6)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive7+trueNegative7)/(totalPositive7+totalNegative7), (falsePositive7 / (falsePositive7 + trueNegative7)), (truePositive7 / (truePositive7 + falsePositive7)), (truePositive7 / (truePositive7 + falseNegative7)))
	fmt.Printf("        TWED & $d_{2}$   & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive8+trueNegative8)/(totalPositive8+totalNegative8), (falsePositive8 / (falsePositive8 + trueNegative8)), (truePositive8 / (truePositive8 + falsePositive8)), (truePositive8 / (truePositive8 + falseNegative8)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\bottomrule\n", (truePositive9+trueNegative9)/(totalPositive9+totalNegative9), (falsePositive9 / (falsePositive9 + trueNegative9)), (truePositive9 / (totalPositive9 + falsePositive9)), (truePositive9 / (truePositive9 + falseNegative9)))
}

func get3dPoints(x []int) []int {
	d := make([]int, len(x)*3)
	sort.Ints(x[:])
	for i := range x {
		for j := 0; j < 3; j++ {
			d[3*i+j] = 3*x[i] + j
		}
	}
	return d
}

func get2dPoints(x []int) []int {
	d := make([]int, len(x)*2)
	sort.Ints(x[:])
	for i := range x {
		for j := 0; j < 2; j++ {
			d[2*i+j] = 3*x[i] + j
		}
	}
	return d
}

func TestExperiment1FaceAuthAll(t *testing.T) {
	// sheetname := "Orientation"
	// cols := []int{2, 5, 8}
	// p := 2
	// k := 1

	// cols := []int{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314, 315, 316, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356, 357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390, 391, 392, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 418, 419, 420, 421, 422, 423, 424, 425, 426, 427, 428, 429, 430, 431, 432, 433, 434, 435, 436, 437, 438, 439, 440, 441, 442, 443, 444, 445, 446, 447, 448, 449, 450, 451, 452, 453, 454, 455, 456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477, 478, 479, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511, 512, 513, 514, 515, 516, 517, 518, 519, 520, 521, 522, 523, 524, 525, 526, 527, 528, 529, 530, 531, 532, 533, 534, 535, 536, 537, 538, 539, 540, 541, 542, 543, 544, 545, 546, 547, 548, 549, 550, 551, 552, 553, 554, 555, 556, 557, 558, 559, 560, 561, 562, 563, 564, 565, 566, 567, 568, 569, 570, 571, 572, 573, 574, 575, 576, 577, 578, 579, 580, 581, 582, 583, 584, 585, 586, 587, 588, 589, 590, 591, 592, 593, 594, 595, 596, 597, 598, 599, 600, 601, 602, 603, 604, 605, 606, 607, 608, 609, 610, 611, 612, 613, 614, 615, 616, 617, 618, 619, 620, 621, 622, 623, 624, 625, 626, 627, 628, 629, 630, 631, 632, 633, 634, 635, 636, 637, 638, 639, 640, 641, 642, 643, 644, 645, 646, 647, 648, 649, 650, 651, 652, 653, 654, 655, 656, 657, 658, 659, 660, 661, 662, 663, 664, 665, 666, 667, 668, 669, 670, 671, 672, 673, 674, 675, 676, 677, 678, 679, 680, 681, 682, 683, 684, 685, 686, 687, 688, 689, 690, 691, 692, 693, 694, 695, 696, 697, 698, 699, 700, 701, 702, 703, 704, 705, 706, 707, 708, 709, 710, 711, 712, 713, 714, 715, 716, 717, 718, 719, 720, 721, 722, 723, 724, 725, 726, 727, 728, 729, 730, 731, 732, 733, 734, 735, 736, 737, 738, 739, 740, 741, 742, 743, 744, 745, 746, 747, 748, 749, 750, 751, 752, 753, 754, 755, 756, 757, 758, 759, 760, 761, 762, 763, 764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777, 778, 779, 780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 793, 794, 795, 796, 797, 798, 799, 800, 801, 802, 803, 804, 805, 806, 807, 808, 809, 810, 811, 812, 813, 814, 815, 816, 817, 818, 819, 820, 821, 822, 823, 824, 825, 826, 827, 828, 829, 830, 831, 832, 833, 834, 835, 836, 837, 838, 839, 840, 841, 842, 843, 844, 845, 846, 847, 848, 849, 850, 851, 852, 853, 854, 855, 856, 857, 858, 859, 860, 861, 862, 863, 864, 865, 866, 867, 868, 869, 870, 871, 872, 873, 874, 875, 876, 877, 878, 879, 880, 881, 882, 883, 884, 885, 886, 887, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898, 899, 900, 901, 902, 903, 904, 905, 906, 907, 908, 909, 910, 911, 912, 913, 914, 915, 916, 917, 918, 919, 920, 921, 922, 923, 924, 925, 926, 927, 928, 929, 930, 931, 932, 933, 934, 935, 936, 937, 938, 939, 940, 941, 942, 943, 944, 945, 946, 947, 948, 949, 950, 951, 952, 953, 954, 955, 956, 957, 958, 959, 960, 961, 962, 963, 964, 965, 966, 967, 968, 969, 970, 971, 972, 973, 974, 975, 976, 977, 978, 979, 980, 981, 982, 983, 984, 985, 986, 987, 988, 989, 990, 991, 992, 993, 994, 995, 996, 997, 998, 999, 1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037, 1038, 1039, 1040, 1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078, 1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093, 1094, 1095, 1096, 1097, 1098, 1099, 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127, 1128, 1129, 1130, 1131, 1132, 1133, 1134, 1135, 1136, 1137, 1138, 1139, 1140, 1141, 1142, 1143, 1144, 1145, 1146, 1147, 1148, 1149, 1150, 1151, 1152, 1153, 1154, 1155, 1156, 1157, 1158, 1159, 1160, 1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168, 1169, 1170, 1171, 1172, 1173, 1174, 1175, 1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211, 1212, 1213, 1214, 1215, 1216, 1217, 1218, 1219, 1220, 1221, 1222, 1223, 1224, 1225, 1226, 1227, 1228, 1229, 1230, 1231, 1232, 1233, 1234, 1235, 1236, 1237, 1238, 1239, 1240, 1241, 1242, 1243, 1244, 1245, 1246, 1247, 1248, 1249, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 1259, 1260, 1261, 1262, 1263, 1264, 1265, 1266, 1267, 1268, 1269, 1270, 1271, 1272, 1273, 1274, 1275, 1276, 1277, 1278, 1279, 1280, 1281, 1282, 1283, 1284, 1285, 1286, 1287, 1288, 1289, 1290, 1291, 1292, 1293, 1294, 1295, 1296, 1297, 1298, 1299, 1300, 1301, 1302, 1303, 1304, 1305, 1306, 1307, 1308, 1309, 1310, 1311, 1312, 1313, 1314, 1315, 1316, 1317, 1318, 1319, 1320, 1321, 1322, 1323, 1324, 1325, 1326, 1327, 1328, 1329, 1330, 1331, 1332, 1333, 1334, 1335, 1336, 1337, 1338, 1339, 1340, 1341, 1342, 1343, 1344, 1345, 1346, 1347, 1348, 1349, 1350, 1351, 1352, 1353, 1354, 1355, 1356, 1357, 1358, 1359, 1360, 1361, 1362, 1363, 1364, 1365, 1366, 1367, 1368, 1369, 1370, 1371, 1372, 1373, 1374, 1375, 1376, 1377, 1378, 1379, 1380, 1381, 1382, 1383, 1384, 1385, 1386, 1387, 1388, 1389, 1390, 1391, 1392, 1393, 1394, 1395, 1396, 1397, 1398, 1399, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407, 1408, 1409, 1410, 1411, 1412, 1413, 1414, 1415, 1416, 1417, 1418, 1419, 1420, 1421, 1422, 1423, 1424, 1425, 1426, 1427, 1428, 1429, 1430, 1431, 1432, 1433}
	// cols := []int{138, 139, 140, 165, 166, 167, 558, 559, 560, 828, 829, 830, 855, 856, 857, 1230, 1231, 1232}

	// cols := get3dPoints([]int{
	// 46, 55, 186, 276, 285, 410})

	cols := get2dPoints(append([]int{}, faceLandmarks...))
	p := 2
	k := 1

	truePositive1, falsePositive1, trueNegative1, falseNegative1, totalPositive1, totalNegative1 := experiment1faceauth(computeDiagSum, localDistanceManhattan, cols, p, k)
	truePositive2, falsePositive2, trueNegative2, falseNegative2, totalPositive2, totalNegative2 := experiment1faceauth(computeDiagSum, localDistanceEuclidean, cols, p, k)
	truePositive3, falsePositive3, trueNegative3, falseNegative3, totalPositive3, totalNegative3 := experiment1faceauth(computeDiagSum, localDistanceChebyshev, cols, p, k)
	truePositive4, falsePositive4, trueNegative4, falseNegative4, totalPositive4, totalNegative4 := experiment1faceauth(computeDTW, localDistanceManhattan, cols, p, k)
	truePositive5, falsePositive5, trueNegative5, falseNegative5, totalPositive5, totalNegative5 := experiment1faceauth(computeDTW, localDistanceEuclidean, cols, p, k)
	truePositive6, falsePositive6, trueNegative6, falseNegative6, totalPositive6, totalNegative6 := experiment1faceauth(computeDTW, localDistanceChebyshev, cols, p, k)
	truePositive7, falsePositive7, trueNegative7, falseNegative7, totalPositive7, totalNegative7 := experiment1faceauth(computeTWED, localDistanceManhattan, cols, p, k)
	truePositive8, falsePositive8, trueNegative8, falseNegative8, totalPositive8, totalNegative8 := experiment1faceauth(computeTWED, localDistanceEuclidean, cols, p, k)
	truePositive9, falsePositive9, trueNegative9, falseNegative9, totalPositive9, totalNegative9 := experiment1faceauth(computeTWED, localDistanceChebyshev, cols, p, k)

	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive1+trueNegative1)/(totalPositive1+totalNegative1), (falsePositive1 / (falsePositive1 + trueNegative1)), (truePositive1 / (truePositive1 + falsePositive1)), (truePositive1 / (truePositive1 + falseNegative1)))
	fmt.Printf("        sum & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive2+trueNegative2)/(totalPositive2+totalNegative2), (falsePositive2 / (falsePositive2 + trueNegative2)), (truePositive2 / (truePositive2 + falsePositive2)), (truePositive2 / (truePositive2 + falseNegative2)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive3+trueNegative3)/(totalPositive3+totalNegative3), (falsePositive3 / (falsePositive3 + trueNegative3)), (truePositive3 / (totalPositive3 + falsePositive3)), (truePositive3 / (truePositive3 + falseNegative3)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive4+trueNegative4)/(totalPositive4+totalNegative4), (falsePositive4 / (falsePositive4 + trueNegative4)), (truePositive4 / (truePositive4 + falsePositive4)), (truePositive4 / (truePositive4 + falseNegative4)))
	fmt.Printf("        DTW & $d_{2}$    & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive5+trueNegative5)/(totalPositive5+totalNegative5), (falsePositive5 / (falsePositive5 + trueNegative5)), (truePositive5 / (truePositive5 + falsePositive5)), (truePositive5 / (truePositive5 + falseNegative5)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\midrule\n", (truePositive6+trueNegative6)/(totalPositive6+totalNegative6), (falsePositive6 / (falsePositive6 + trueNegative6)), (truePositive6 / (totalPositive6 + falsePositive6)), (truePositive6 / (truePositive6 + falseNegative6)))
	fmt.Printf("         & $d_{1}$       & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive7+trueNegative7)/(totalPositive7+totalNegative7), (falsePositive7 / (falsePositive7 + trueNegative7)), (truePositive7 / (truePositive7 + falsePositive7)), (truePositive7 / (truePositive7 + falseNegative7)))
	fmt.Printf("        TWED & $d_{2}$   & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\\n", (truePositive8+trueNegative8)/(totalPositive8+totalNegative8), (falsePositive8 / (falsePositive8 + trueNegative8)), (truePositive8 / (truePositive8 + falsePositive8)), (truePositive8 / (truePositive8 + falseNegative8)))
	fmt.Printf("         & $d_{\\infty}$  & $%.4f$ & $%.4f$ & $%.2f$ & $%.2f$ \\\\  \\bottomrule\n", (truePositive9+trueNegative9)/(totalPositive9+totalNegative9), (falsePositive9 / (falsePositive9 + trueNegative9)), (truePositive9 / (totalPositive9 + falsePositive9)), (truePositive9 / (truePositive9 + falseNegative9)))

	// fmt.Println(cols)
}

func TestExperiment1ShakeAuthMultiP(t *testing.T) {
	// sheetname := "Orientation"
	// cols := []int{2, 5, 8}
	// p := 2
	// k := 1

	sheetnames := []string{"Gyroscope", "Accelerometer"}
	cols := []int{2, 3, 4, 2, 3, 4}

	// sheetnames := []string{"Orientation"}
	// cols := []int{2, 5, 8}

	s1 := []string{"$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$"}
	s2 := []string{"", "sum", "", "", "DTW", "", "", "TWED", ""}
	sds := []seriesdistfunc{computeDiagSum, computeDiagSum, computeDiagSum, computeDTW, computeDTW, computeDTW, computeTWED, computeTWED, computeTWED}
	lds := []localdistfunc{localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev}

	ps := []int{0, 1, 2}
	k := 3

	result := ""
	line0 := "    \\begin{tabular}{cc"
	line1 := "      \\multicolumn{2}{c}{distance} "
	line2 := "      series & local "
	for _, p := range ps {
		line0 += "|cc"
		line1 += "& \\multicolumn{2}{c|}{$q=" + strconv.Itoa(p) + "$} "
		line2 += "& FAR & FRR "
	}
	line0 += "|}\n"
	line1 += "\\\\\n"
	line2 += "\\\\\n \\toprule"

	for i := range s1 {
		result += "      " + s2[i] + "  & " + s1[i] + " "
		for _, p := range ps {
			truePositive1, falsePositive1, trueNegative1, falseNegative1, _, _ := experiment1shakeauth(sds[i], lds[i], sheetnames, cols, p, k)
			result += fmt.Sprintf("& $%.4f$ & $%.4f$ ", (falsePositive1 / (falsePositive1 + trueNegative1)), (falseNegative1 / (truePositive1 + falseNegative1)))
		}
		result += "\\\\\n"
	}

	fmt.Println()

	fmt.Print(line0)
	fmt.Print(line1)
	fmt.Print(line2)
	fmt.Println(result + "      \\bottomrule\n    \\end{tabular}")
}

func TestExperiment1BlowAuthMultiP(t *testing.T) {
	cols := []int{1}

	s1 := []string{"$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$"}
	s2 := []string{"", "sum", "", "", "DTW", "", "", "TWED", ""}
	sds := []seriesdistfunc{computeDiagSum, computeDiagSum, computeDiagSum, computeDTW, computeDTW, computeDTW, computeTWED, computeTWED, computeTWED}
	lds := []localdistfunc{localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev}

	ps := []int{0, 1, 2}
	k := 1

	result := ""
	line0 := "    \\begin{tabular}{cc"
	line1 := "      \\multicolumn{2}{c}{distance} "
	line2 := "      series & local "
	for _, p := range ps {
		line0 += "|cc"
		line1 += "& \\multicolumn{2}{c|}{$q=" + strconv.Itoa(p) + "$} "
		line2 += "& FAR & FRR "
	}
	line0 += "|}\n"
	line1 += "\\\\\n"
	line2 += "\\\\\n \\toprule"

	for i := range s1 {
		result += "      " + s2[i] + "  & " + s1[i] + " "
		for _, p := range ps {
			truePositive1, falsePositive1, trueNegative1, falseNegative1, _, _ := experiment1blowauth(sds[i], lds[i], cols, p, k)
			result += fmt.Sprintf("& $%.4f$ & $%.4f$ ", (falsePositive1 / (falsePositive1 + trueNegative1)), (falseNegative1 / (truePositive1 + falseNegative1)))
		}
		result += "\\\\\n"
	}

	fmt.Println()

	fmt.Print(line0)
	fmt.Print(line1)
	fmt.Print(line2)
	fmt.Println(result + "      \\bottomrule\n    \\end{tabular}")
}

func TestExperiment1FaceAuthMultiP(t *testing.T) {
	cols := get2dPoints(append([]int{}, faceLandmarks...))

	s1 := []string{"$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$", "$d_{1}$", "$d_{2}$", "$d_{\\infty}$"}
	s2 := []string{"", "sum", "", "", "DTW", "", "", "TWED", ""}
	sds := []seriesdistfunc{computeDiagSum, computeDiagSum, computeDiagSum, computeDTW, computeDTW, computeDTW, computeTWED, computeTWED, computeTWED}
	lds := []localdistfunc{localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev, localDistanceManhattan, localDistanceEuclidean, localDistanceChebyshev}

	ps := []int{0, 1, 2}
	k := 1

	result := ""
	line0 := "    \\begin{tabular}{cc"
	line1 := "      \\multicolumn{2}{c}{distance} "
	line2 := "      series & local "
	for _, p := range ps {
		line0 += "|cc"
		line1 += "& \\multicolumn{2}{c|}{$q=" + strconv.Itoa(p) + "$} "
		line2 += "& FAR & FRR "
	}
	line0 += "|}\n"
	line1 += "\\\\\n"
	line2 += "\\\\\n \\toprule"

	for i := range s1 {
		result += "      " + s2[i] + "  & " + s1[i] + " "
		for _, p := range ps {
			truePositive1, falsePositive1, trueNegative1, falseNegative1, _, _ := experiment1faceauth(sds[i], lds[i], cols, p, k)
			result += fmt.Sprintf("& $%.4f$ & $%.4f$ ", (falsePositive1 / (falsePositive1 + trueNegative1)), (falseNegative1 / (truePositive1 + falseNegative1)))
		}
		result += "\\\\\n"
	}

	fmt.Println()

	fmt.Print(line0)
	fmt.Print(line1)
	fmt.Print(line2)
	fmt.Println(result + "      \\bottomrule\n    \\end{tabular}")
}

func TestExperiment1RandomData(t *testing.T) {
	u := 20
	n := 10
	T := 50
	m := 3

	valrange := 100000
	init := int64(100000000)

	allData := make([][][][]int64, u)
	names := make([]string, u)
	for i := range allData {
		allData[i] = make([][][]int64, n)
		for j := range allData[i] {
			allData[i][j] = generateSeries(T, m, init, valrange)
			names[i] = "User" + strconv.Itoa(i+1)
		}
	}

	seriesfunc := computeDTW
	localfunc := localDistanceManhattan

	truePositive, falsePositive, trueNegative, falseNegative, totalPositive, totalNegative := processExperiment1(allData, 1, seriesfunc, localfunc, 3, names)

	fmt.Printf("true positive (precision): %f\n", truePositive/totalPositive)
	fmt.Printf("true negative (recall): %f\n", trueNegative/totalNegative)
	fmt.Printf("false positive: %f\n", falsePositive/totalNegative)
	fmt.Printf("false negative: %f\n", falseNegative/totalPositive)
	fmt.Printf("accuracy: %f\n", (truePositive+trueNegative)/(totalPositive+totalNegative))
}
func TestExperiment2Euclidean(t *testing.T) {
	Ts := []int{10, 15, 25, 35, 50, 75, 100, 150, 250, 350, 500, 750, 1000, 1500, 2500, 3500, 5000, 7500, 10000}
	m := 3

	N := 100

	// times := []int64{}

	for i, T := range Ts {
		// Reset per T: this accumulator must measure only the current T,
		// not the running total over every T seen so far.
		totTime := int64(0)
		for n := 0; n < N; n++ {
			series1 := generateSeries(T, m, 1000000, 10000)
			series2 := generateSeries(T, m, 1000000, 10000)

			genstartttime := time.Now().UnixNano()
			computeDiagSum(series1, series2, 0, localDistanceEuclidean)
			genendtime := time.Now().UnixNano()

			totTime += genendtime - genstartttime
		}

		fmt.Printf("(%d,%f)", Ts[i], float64(totTime)/float64(N*1000000000))
	}

	fmt.Println()

	for i, T := range Ts {
		// Reset per T: this accumulator must measure only the current T,
		// not the running total over every T seen so far.
		totTime := int64(0)
		for n := 0; n < N; n++ {
			series1 := generateSeries(T, m, 1000000, 10000)
			series2 := generateSeries(T, m, 1000000, 10000)

			genstartttime := time.Now().UnixNano()
			computeDTW(series1, series2, 0, localDistanceEuclidean)
			genendtime := time.Now().UnixNano()

			totTime += genendtime - genstartttime
		}

		fmt.Printf("(%d,%f)", Ts[i], float64(totTime)/float64(N*1000000000))
	}

	fmt.Println()

	for i, T := range Ts {
		// Reset per T: this accumulator must measure only the current T,
		// not the running total over every T seen so far.
		totTime := int64(0)
		for n := 0; n < N; n++ {
			series1 := generateSeries(T, m, 1000000, 10000)
			series2 := generateSeries(T, m, 1000000, 10000)

			genstartttime := time.Now().UnixNano()
			computeTWED(series1, series2, 0, localDistanceEuclidean)
			genendtime := time.Now().UnixNano()

			totTime += genendtime - genstartttime
		}

		fmt.Printf("(%d,%f)", Ts[i], float64(totTime)/float64(N*1000000000))
	}

	fmt.Println()
}


// participantNames returns the folder names of the datasets: Participant-1, ..., Participant-n.
func participantNames(n int) []string {
	names := make([]string, n)
	for i := range names {
		names[i] = "Participant-" + strconv.Itoa(i+1)
	}
	return names
}

// faceLandmarks are the MediaPipe landmarks of the facial landmark case study: the corners and
// the centers of the upper and lower lip. The original design used 17 landmarks:
// 61, 0, 291, 17 (mouth), 4 (tip of the nose), 263, 374, 362, 386 (left eye),
// 33, 159, 133, 145 (right eye), 285, 276 (left brow), 46, 55 (right brow).
var faceLandmarks = []int{61, 0, 291, 17}
