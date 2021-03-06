package utils.Summarizer;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.WordToSentenceProcessor;
import play.Logger;
import utils.Constants;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by brianzhao on 10/31/15.
 */


public class Transcript {
    private static final org.slf4j.Logger logger = Logger.of(Transcript.class).underlying();
    private List<TimeRegion> timeRegions;
    private String entireTranscript;
    private String transcriptWithoutTimeValues; //this contains just the words said, used for
    private AllStringData allStringData = new AllStringData();
    private static StopWords stopWords = new StopWords();
    private double durationOfVideo;
    private boolean analyzedYet = false;
    private boolean importanceValuesSet = false;
    private Boolean isAutoGenerated = null;

    public Transcript(String entireTranscript) {
        this.entireTranscript = entireTranscript;
        timeRegions = buildTimeRegionsFromString(entireTranscript);
    }

    /**
     * takes the transcript string, which has times and words said during times
     * and returns an List of timeregions
     *
     * @param entireTranscript
     * @return
     */
    private List<TimeRegion> buildTimeRegionsFromString(String entireTranscript) {
        List<TimeRegion> timeRegionList = new ArrayList<>();
        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(entireTranscript.getBytes())));
            StringBuilder transcriptWordsWithoutTimes = new StringBuilder();
            String currentLine;
            String startTime = null;
            String endTime = null;

            int counter = 0;
            while ((currentLine = bufferedReader.readLine()) != null) {
                if (counter % 2 == 0) {
                    /*
                        if its even, then its a time value; works because transcriptgenerator guarantees
                        that there are no newlines within the captions themselves
                    */
                    String time = currentLine;
                    String[] timeArray = currentLine.trim().split(Constants.TIME_REGION_DELIMITER);
                    if (timeArray.length != 2) {
                        throw new RuntimeException("Expected a start time and end time after splitting on time region delimiter. Array size not 2");
                    }
                    startTime = timeArray[0];
                    endTime = timeArray[1];
                } else {
                    if (startTime == null || endTime == null) {
                        throw new RuntimeException("StartTime or EndTime was null. Should never happen");
                    }
                    timeRegionList.add(new TimeRegion(startTime, endTime, currentLine));
                    startTime = null;
                    endTime = null;
                    transcriptWordsWithoutTimes.append(currentLine).append(" ");
                }
                counter++;
            }
            if (counter % 2 != 0) {
                throw new RuntimeException("Number of lines was not even. Means bufferedreader didn't get everything! Or there's an extra newline");
            }
            this.transcriptWithoutTimeValues = transcriptWordsWithoutTimes.toString();
            this.durationOfVideo = timeRegionList.get(timeRegionList.size() - 1).getEndTimeSeconds();

            /**
             * check if the transcript was auto-generted by google
             * if it was then reconstruct the "toReturn" time regions based on sentences
             * otherwise,
             *
             * NOTE: currently it seems as though this actually makes the summaries worse, needs experimentation
             */
//            if (!isAutoGenerated()) {
//                List<String> allSentences = getListOfSentences(this.transcriptWithoutTimeValues);
//                timeRegionList = createTimeRegionsBasedOnSentences(allSentences, timeRegionList);
//            }
            return timeRegionList;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("IOException while trying to make transcript object from string");
        }
    }

    /**
     * takes a list of sentences and the timeregions that currently contain these sentences
     * returns a new list with timeregions consisting of sentences of the sentence calculated
     * by the proportion of duration of the timeregion (based on length of characters into that
     * captionString of the timeregion)
     * <p>
     * <p>
     * NOTE: THIS CODE WAS A PAIN TO WRITE AND WILL BE A PAIN TO UNDERSTAND!!!
     *
     * @param allSentences
     * @param timeRegionList
     * @return
     */
    private static List<TimeRegion> createTimeRegionsBasedOnSentences(List<String> allSentences, List<TimeRegion> timeRegionList) {
        //start off at the beginning of the list of timeregions
        List<TimeRegion> regionsBasedOnSentences = new ArrayList<>();
        int startIndex = 0;
        int endIndex = startIndex;
        int stringIndexWithinCaption = 0;

        for (String sentence : allSentences) {

            /**
             * actually starting time for each sentence depends on how far into the timeregion
             * you are. since a sentence can begin in the middle of the passed in timeregion
             * I will approximate via proportion of characters into timeregion.
             */
            TimeRegion startTimeRegion = timeRegionList.get(startIndex);
            int startSeconds = TimeRegion.calculateSeconds(startTimeRegion.getStartTime())
                    + (int)
                    (
                            startTimeRegion.getDuration() *
                                    ((stringIndexWithinCaption * 1.0) / startTimeRegion.getCaptionString().length())
                    );

            String portionOfSentenceLeft = sentence;
            String currentTimeRegionWords = timeRegionList.get(endIndex).getCaptionString().substring(stringIndexWithinCaption).trim();
            while (portionOfSentenceLeft.contains(currentTimeRegionWords)) {
                int indexOfString = portionOfSentenceLeft.indexOf(currentTimeRegionWords) + currentTimeRegionWords.length();
                portionOfSentenceLeft = portionOfSentenceLeft.substring(indexOfString).trim();
                endIndex++;
                if (portionOfSentenceLeft.length() == 0) {
                    break;
                }
                currentTimeRegionWords = timeRegionList.get(endIndex).getCaptionString().trim();
            }
            /**
             * if: your sentence stopped perfectly at the end of a timeregion
             * else: your sentence stops in the middle of a timeregion
             */

            if (portionOfSentenceLeft.length() == 0) {
                stringIndexWithinCaption = 0;
                regionsBasedOnSentences.add(new TimeRegion(
                        startSeconds,
                        timeRegionList.get(endIndex - 1).getEndTime(),
                        sentence
                ));
            } else {
                if (endIndex == startIndex) {
                    stringIndexWithinCaption = timeRegionList.get(endIndex).getCaptionString().indexOf(portionOfSentenceLeft, stringIndexWithinCaption) + portionOfSentenceLeft.length();
                } else {
                    stringIndexWithinCaption = currentTimeRegionWords.indexOf(portionOfSentenceLeft) + portionOfSentenceLeft.length();
                }

                TimeRegion endTimeRegion = timeRegionList.get(endIndex);
                int endSeconds = TimeRegion.calculateSeconds(endTimeRegion.getStartTime())
                        + (int)
                        (
                                endTimeRegion.getDuration() *
                                        ((stringIndexWithinCaption * 1.0) / endTimeRegion.getCaptionString().length())
                        );
                regionsBasedOnSentences.add(new TimeRegion(
                        startSeconds,
                        endSeconds,
                        sentence
                ));
            }
            startIndex = endIndex;
        }
        logger.debug("Created a transcript with timeregions based on sentences");
        return regionsBasedOnSentences;
    }

    /**
     * this is not code that I wrote
     * sentence splitting is handled via stanford nlp:
     * https://stackoverflow.com/questions/9492707/how-can-i-split-a-text-into-sentences-using-the-stanford-parser
     *
     * @param multipleSentences string representing a paragraph, or multiple sentences together
     * @return
     */
    private static List<String> getListOfSentences(String multipleSentences) {
        List<String> sentenceList = new ArrayList<>();
        List<CoreLabel> tokens = new ArrayList<>();
        PTBTokenizer<CoreLabel> tokenizer = new PTBTokenizer<>(new StringReader(multipleSentences), new CoreLabelTokenFactory(), "");
        while (tokenizer.hasNext()) {
            tokens.add(tokenizer.next());
        }

        // Split sentences from tokens
        List<List<CoreLabel>> sentences = new WordToSentenceProcessor<CoreLabel>().process(tokens);
        // Join back together
        int end;
        int start = 0;
        for (List<CoreLabel> sentence : sentences) {
            end = sentence.get(sentence.size() - 1).endPosition();
            sentenceList.add(multipleSentences.substring(start, end).trim());
            start = end;
        }
        return sentenceList;
    }

    public String getEntireTranscript() {
        return entireTranscript;
    }

    public String getTranscriptWithoutTimeValues() {
        return transcriptWithoutTimeValues;
    }


    /**
     * lazily determines whether this video was auto-generated by youtube's caption system,
     * or was actually manually transcribed
     * does so by determining what proportion of characters in the transcript are made of punctuation
     * <p>
     * <p>
     * after repeated trials the break off proportion is very close to 1.9%
     * i.e. videos who had punctuation for >= 1.9% of their characters were manually transcribed
     * and those who had less were autogenerated
     * <p>
     * <p>
     * 1.9% is our "Constants.DEFAULT_SUMMARY_PROPORTION"
     *
     * @return
     */
    public boolean isAutoGenerated() {
        logger.debug("Determining whether transcript is auto-generated or not...");
        if (isAutoGenerated == null) {
            int originalLength = transcriptWithoutTimeValues.length();
            String withoutPunctuation = transcriptWithoutTimeValues.replaceAll("\\p{Punct}", "");
            int newLength = withoutPunctuation.length();
            double proportionOfPunctuation = ((originalLength - newLength) * 1.0) / originalLength;
            logger.debug("Proportion of punctuation was: {}", proportionOfPunctuation);
            if (proportionOfPunctuation >= Constants.PROPORTION_OF_PUNCTUATION_CUTOFF) {
                isAutoGenerated = false;
            } else {
                isAutoGenerated = true;
            }
        }
        logger.debug("We think the transcript was " + (isAutoGenerated == true ? "" : "not ") + "auto-generated");
        return isAutoGenerated;
    }


    /**
     * call this method to generate all data regarding tf, df and tfidf counts for this transcript
     * will update allStringData, which has data about the entire transcript's tf, df and idf counts
     * will also set each timeregion's localTF count
     */
    public void analyzeWordCount() {
        analyzedYet = true;
        for (TimeRegion timeRegion : timeRegions) {
            //convert all words to lowercase and remove any punctuation
            String[] words = timeRegion.getCaptionString().toLowerCase().replaceAll("[^\\w ]", "").split("\\s+");

            //holder corresponds to the field "localTF" for each timeRegion object
            Map<String, Double> localTf = new HashMap<>();
            for (String currentWord : words) {

                //skip the word if it is a stopword
                if (stopWords.isStopWord(currentWord)) {
                    continue;
                }
                //stem the current word
                String currentWordStemmed = stemWord(currentWord);

                //if the stemmed word isn't inside the hashmap, add it w/a frequency of 1
                if (!localTf.containsKey(currentWordStemmed)) {
                    localTf.put(currentWordStemmed, 1.0);

                    //increment the word's associated document frequency as well, since this is the first time
                    //the word has occurred in this timeregion
                    if (!allStringData.containsString(currentWordStemmed)) {
                        allStringData.addString(currentWordStemmed);
                        allStringData.updateDF(currentWordStemmed, 1.0);
                        allStringData.setUnstemmedVersion(currentWordStemmed, currentWord);
                    } else {
                        allStringData.updateDF(currentWordStemmed, allStringData.getDF(currentWordStemmed) + 1);
                    }
                }
                //otherwise , increment the value in the holder hashmap
                else {
                    localTf.put(currentWordStemmed, localTf.get(currentWordStemmed) + 1);
                }
            }
            localTf.remove("");
            for (String containedWord : localTf.keySet()) {
                //increment the global tf frequency associated with this word
                if (!allStringData.containsString(containedWord)) {
                    throw new RuntimeException("All string data should have picked up this word when updating Document Frequency");
                } else {
                    allStringData.updateTF(containedWord, allStringData.getTF(containedWord) + localTf.get(containedWord));
                }
            }
            timeRegion.setLocalTF(localTf);
        }
        allStringData.removeEmptyString();
        //calculate all tf-idf frequencies
        for (String containedString : allStringData.allContainedStrings()) {
            allStringData.updateTfIdf(containedString, computeTf_Idf(containedString));
        }
    }

    public boolean isAnalyzedYet() {
        return analyzedYet;
    }

    public List<TimeRegion> getTimeRegions() {
        return timeRegions;
    }

    private double computeTf_Idf(String t) {
        double logNormalizedTf = 1 + Math.log10(allStringData.getTF(t)); //log normalized tf
        double inverseDf = Math.log10(timeRegions.size() / allStringData.getDF(t)); //inverse freq
        return logNormalizedTf * inverseDf;
    }

    public double getDurationOfVideo() {
        return durationOfVideo;
    }

    /**
     * be sure to not modify any of the string data contained within here.
     *
     * @return
     */
    public AllStringData getAllStringData() {
        return allStringData;
    }

    public String stemWord(String inputString) {
        Stemmer s = new Stemmer();
        char[] word = inputString.toCharArray();
        s.add(word, word.length);
        s.stem();
        return s.toString();
    }

    public boolean isImportanceValuesSet() {
        return importanceValuesSet;
    }

    public void setImportanceValuesSet(boolean importanceValuesSet) {
        this.importanceValuesSet = importanceValuesSet;
    }


    @Override
    public String toString() {
        return entireTranscript;
    }
}
