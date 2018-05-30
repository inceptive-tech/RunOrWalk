/*
 * Copyright 2018 Inceptive
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tech.inceptive.oss.runorwalk;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Andres Bel Alonso
 */
public class ReorderDataset {

    public static class LineData implements Comparable<LineData> {

        private final String[] data;
        private final LocalDate date;
        private final LocalTime time;

        public LineData(String[] data, LocalDate date, LocalTime time) {
            this.data = data;
            this.date = date;
            this.time = time;
        }

        @Override
        public int compareTo(LineData o) {
            if(this.date.isEqual(o.date)) {
                return Long.compare(this.time.toNanoOfDay(), o.time.toNanoOfDay());
            } else {
                return this.date.compareTo(o.date);
            }
        }

        public String[] getData() {
            return data;
        }

    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws FileNotFoundException {
        String csvPath = "/home/andres/Bureau/Kaggle/Datasets/Run Or Walk/dataset.csv";
        String separator = ",";
        boolean hasHeader = true;
        String escapeChar = "\"";
        String destCSV = "/home/andres/Bureau/Kaggle/Datasets/Run Or Walk/dataset_order.csv";
        CSVReader reader = new CSVReader(separator, csvPath, hasHeader, "UTF8", escapeChar);
        List<LineData> lineData = new ArrayList<>();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("y-M-d");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("H:m:s:n");
        //Read the csv
        while (CSVReader.secureReadNextLine(reader)) {
            lineData.add(new LineData(reader.getSeparetedCurLine(),
                    LocalDate.parse(reader.getColName("date"), dateFormatter),
                    LocalTime.parse(reader.getColName("time"), timeFormatter)));
        }
        //write the csv
        PrintWriter pw = new PrintWriter(new File(destCSV));
        String[] header = reader.getHeader();
        StringBuilder headerSB = new StringBuilder();
        headerSB.append(header[0]);
        for (int i = 1; i < header.length; i++) {
            headerSB.append(separator);
            headerSB.append(header[i]);
        }
        pw.println(headerSB.toString());
        lineData.stream().
                sorted().
                map(LineData::getData).
                forEach(d -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(d[0]);
                    for(int i=1; i<d.length; i++) {
                        sb.append(separator);
                        sb.append(d[i]);
                    }
                    pw.println(sb.toString());
                });
        pw.close();
    }

}
