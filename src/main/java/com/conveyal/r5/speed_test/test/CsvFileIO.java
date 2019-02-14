package com.conveyal.r5.speed_test.test;

import com.conveyal.r5.profile.entur.util.TimeUtils;
import com.csvreader.CsvReader;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * This class is responsible for reading and writing test cases and test case results to CSV files.
 */
public class CsvFileIO {
    private static final int NOT_SET = -1;
    private static final Charset CHARSET_UTF_8 = Charset.forName("UTF-8");
    private static final char CSV_DELIMITER = ',';
    private static boolean printResultsForFirstStrategyRun = true;

    private final File testCasesFile;
    private final File expectedResultsFile;
    private final File expectedResultsOutputFile;


    public CsvFileIO(File dir, String testSetName) {
        testCasesFile = new File(dir, testSetName + ".csv");
        expectedResultsFile = new File(dir, testSetName + "-expected-results.csv");
        expectedResultsOutputFile = new File(dir, testSetName + "-expected-results-out.csv");
    }

    /**
     * CSV input order matches constructor:
     * <pre>
     *   id, description,fromPlace,fromLat,fromLon,origin,toPlace,toLat,toLon,destination,transportType,expectedResult
     * </pre>
     */
    public List<TestCase> readTestCasesFromFile() throws IOException {
        Map<String, TestCaseResults> expectedResults = readExpectedResultsFromFile();
        List<TestCase> testCases = new ArrayList<>();
        CsvReader csvReader = new CsvReader(testCasesFile.getAbsolutePath(), CSV_DELIMITER, CHARSET_UTF_8);
        csvReader.readHeaders(); // Skip header

        while (csvReader.readRecord()) {
            if(isCommentOrEmpty(csvReader.getRawRecord())) {
                continue;
            }
            String id = csvReader.get("testCaseId");
            TestCase tc = new TestCase(
                    id,
                    TimeUtils.parseHHMM(csvReader.get("departure"), NOT_SET),
                    TimeUtils.parseHHMM(csvReader.get("arrival"), NOT_SET),
                    TimeUtils.parseHHMM(csvReader.get("window"), NOT_SET),
                    csvReader.get("description"),
                    csvReader.get("origin"),
                    csvReader.get("fromPlace"),
                    Double.parseDouble(csvReader.get("fromLat")),
                    Double.parseDouble(csvReader.get("fromLon")),
                    csvReader.get("destination"),
                    csvReader.get("toPlace"),
                    Double.parseDouble(csvReader.get("toLat")),
                    Double.parseDouble(csvReader.get("toLon")),
                    expectedResults.get(id)
            );
            testCases.add(tc);
        }
        return testCases;
    }

    /**
     * Write all results to a CSV file. This file can be renamed and used as expected-result input file.
     */
    public void writeResultsToFile(List<TestCase> testCases) {
        if (!printResultsForFirstStrategyRun || testCases.stream().anyMatch(TestCase::notRun)) {
            return;
        }
        printResultsForFirstStrategyRun = false;

        try (PrintWriter out = new PrintWriter(expectedResultsOutputFile, CHARSET_UTF_8.name())) {
            out.println("tcId,transfers,duration,walkDistance,startTime,endTime,details");

            for (TestCase tc : testCases) {
                for (Result result : tc.actualResults()) {
                    out.print(tc.id);
                    out.print(CSV_DELIMITER);
                    out.print(result.transfers);
                    out.print(CSV_DELIMITER);
                    out.print(result.duration);
                    out.print(CSV_DELIMITER);
                    out.print(result.walkDistance);
                    out.print(CSV_DELIMITER);
                    out.print(result.startTime);
                    out.print(CSV_DELIMITER);
                    out.print(result.endTime);
                    out.print(CSV_DELIMITER);
                    out.print(result.details);
                    out.print('\n');
                }
            }
            out.flush();
            System.err.println("\nINFO - New CSV file with results is saved to '" + expectedResultsOutputFile.getAbsolutePath() + "'.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /* private methods */

    private Map<String, TestCaseResults> readExpectedResultsFromFile() throws IOException {
        if(!expectedResultsFile.exists()) {
            return Collections.emptyMap();
        }

        Map<String, TestCaseResults> results = new HashMap<>();
        CsvReader csvReader = new CsvReader(expectedResultsFile.getAbsolutePath(), CSV_DELIMITER, CHARSET_UTF_8);
        csvReader.readRecord(); // Skip header

        while (csvReader.readRecord()) {
            Result expRes = readExpectedResult(csvReader);
            results.computeIfAbsent(expRes.testCaseId, TestCaseResults::new).addExpectedResult(expRes);
        }
        return results;
    }

    private Result readExpectedResult(CsvReader csvReader) throws IOException {
            return new Result(
                    csvReader.get(0),
                    Integer.parseInt(csvReader.get(1)),
                    Integer.parseInt(csvReader.get(2)),
                    Integer.parseInt(csvReader.get(3)),
                    csvReader.get(4),
                    csvReader.get(5),
                    csvReader.get(6)
            );
    }

    private boolean isCommentOrEmpty(String line) {
        return line.startsWith("#") || line.matches("[\\s,;]*");
    }
}
