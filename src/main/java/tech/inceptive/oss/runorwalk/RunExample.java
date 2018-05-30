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

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration.ListBuilder;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.InMemoryStatsStorage;
import org.nd4j.jita.conf.Configuration;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;

/**
 *
 * @author Andres Bel Alonso
 */
public class RunExample {

    private static final Logger LOGGER = LogManager.getLogger(RunExample.class);

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException, InterruptedException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        
        // params
        int interNeurons = 8;
        int guiPort = 9300;
        int cpuPriority = 0;
        int gpuPriority = 1;
        int nbIterations = 1500;
        double learningRate = 0.01;
        String csvPath = "/home/andres/Bureau/Kaggle/Datasets/Run Or Walk/dataset.csv";


        // Backend options
        // Please note that this way to set environement variables only works on Linux OS (tested on Ubuntu 16.04). 
        // On windows 10, it does not work
        Map<String, String> env = System.getenv();
        Class<?> cl = env.getClass();
        Field field = cl.getDeclaredField("m");
        field.setAccessible(true);
        Map<String, String> writableEnv = (Map<String, String>) field.get(env);
        writableEnv.put("BACKEND_PRIORITY_CPU", Integer.toString(cpuPriority));
        writableEnv.put("BACKEND_PRIORITY_GPU", Integer.toString(gpuPriority));
        
        // CUDA options
        CudaEnvironment.getInstance().getConfiguration()
                .setMaximumDeviceCacheableLength(1024 * 1024 * 1024L)
                .setMaximumDeviceCache(6L * 1024 * 1024 * 1024)
                .setMaximumHostCacheableLength(1024 * 1024 * 1024L)
                .setMaximumHostCache(6L * 1024 * 1024 * 1024L)
                .setAllocationModel(Configuration.AllocationModel.CACHE_HOST)
                .setMemoryModel(Configuration.MemoryModel.DELAYED);
        CudaEnvironment.getInstance().notifyConfigurationApplied();

        // ********* Building neural network ***********
        NeuralNetConfiguration.Builder builder = new NeuralNetConfiguration.Builder();
        // The number of iterations in the training
        builder.iterations(nbIterations);
        // The initial part of the gradient that will be use in each iteration.
        // A low value will slow the training, but higher value can make the network diverge
        builder.learningRate(learningRate);
        // THE algotihm to train neural networks
        builder.optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT);
        builder.seed(123);
        builder.biasInit(0);
        // we use all the data each time we update the network
        builder.miniBatch(false);
        // A standard correct choices
        builder.updater(Updater.RMSPROP);
        builder.weightInit(WeightInit.XAVIER);
        ListBuilder listBuilder = builder.list();
        GravesLSTM.Builder hiddenLayerBuilder = new GravesLSTM.Builder();
        // There are 6 variables that will be used (3 for the gyroscope,
        // 3 for the the accelerometer)
        hiddenLayerBuilder.nIn(6);
        hiddenLayerBuilder.nOut(interNeurons);
        // adopted activation function from GravesLSTMCharModellingExample
        // seems to work well with RNNs
        hiddenLayerBuilder.activation(Activation.SIGMOID);
        // we add the layer in first position
        listBuilder.layer(0, hiddenLayerBuilder.build());
        RnnOutputLayer.Builder outputLayerBuilder = new RnnOutputLayer.Builder(LossFunction.MCXENT);
//        RnnOutputLayer.Builder outputLayerBuilder = new RnnOutputLayer.Builder(LossFunction.SQUARED_LOSS);
        // softmax normalizes the output neurons, the sum of all outputs is 1
        // this is required for our sampleFromDistribution-function
        outputLayerBuilder.activation(Activation.SOFTMAX);
        outputLayerBuilder.nIn(interNeurons);
        outputLayerBuilder.nOut(2);
        listBuilder.layer(1, outputLayerBuilder.build());
        // specify if the network must be trained
        listBuilder.pretrain(false);
        listBuilder.backprop(true);
        MultiLayerConfiguration conf = listBuilder.build();
        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();
        // Here we set the port that will be used to deploy the graphical interface
        System.setProperty("org.deeplearning4j.ui.port", Integer.toString(guiPort));
        UIServer uiServer = UIServer.getInstance();
        StatsStorage statsStorage = new InMemoryStatsStorage();
        uiServer.attach(statsStorage);
        net.setListeners(new ScoreIterationListener(5), new StatsListener(statsStorage, 5));

        // ********** Import data ************
        CSVReader reader = new CSVReader(",", csvPath, true);
        LocalTime lastTime = null;
        LocalDate lastDate = null;
        List<INDArray> timeSeries = new ArrayList<>();
        List<INDArray> labels = new ArrayList<>();
        List<double[]> curTimeSerie = new ArrayList<>();
        List<double[]> curLabel = new ArrayList<>();
        int counter = 0;
        while (reader.readNextLine()) {
            if (counter % 1000 == 0) {
                LOGGER.debug("Processing line {}", counter);
            }
            double accelX = Double.parseDouble(reader.getColName("acceleration_x"));
            double accelY = Double.parseDouble(reader.getColName("acceleration_y"));
            double accelZ = Double.parseDouble(reader.getColName("acceleration_z"));
            double gyroX = Double.parseDouble(reader.getColName("gyro_x"));
            double gyroY = Double.parseDouble(reader.getColName("gyro_y"));
            double gyroZ = Double.parseDouble(reader.getColName("gyro_z"));
            double wrist = Double.parseDouble(reader.getColName("wrist"));
            double activity = Double.parseDouble(reader.getColName("activity"));
            String date = reader.getColName("date");
            String time = reader.getColName("time");
            // Transform the time string into a java.time.LocalTime
            LocalTime curTime = getCurTime(time);
            // Transform the the date string into a java.time.LocalDate
            LocalDate curDate = getCurDate(date);
            // Determinates if this is a new time serie
            if (!isTheSameTimeSerie(lastTime, lastDate, curTime, curDate)) {
                // we create the new INDArrays and we move to the next 
                if (lastTime != null) {
                    // This is not the first time
                    INDArray timeSerie = buildNDArray(curTimeSerie);
                    INDArray label = buildNDArray(curLabel);
                    timeSeries.add(timeSerie);
                    labels.add(label);
                    curTimeSerie.clear();
                    curLabel.clear();
                }
            }
            double[] curValsTab = new double[]{accelX, accelY, accelZ, gyroX, gyroY, gyroZ};
//            double[] curLabelTab = new double[]{wrist,activity};
//            double[] curLabelTab = new double[]{wrist==0?1:0,wrist==1?1:0};
            double[] curLabelTab = new double[]{activity == 0 ? 1 : 0, activity == 1 ? 1 : 0};
            curTimeSerie.add(curValsTab);
            curLabel.add(curLabelTab);
            lastTime = curTime;
            lastDate = curDate;
            counter++;
        }
        // train test separation
        double trainRatio = 0.7;
        List<INDArray> timeSeriesTest = new ArrayList<>();
        List<INDArray> testLabels = new ArrayList<>();
        int trainSize = (int) (timeSeries.size() * trainRatio);
        Random random = new Random(33);
        int maxTimeSeriesReali = timeSeries.stream().mapToInt(t -> t.rows()).max().getAsInt();
        while (timeSeries.size() > trainSize) {
            int index = random.nextInt(timeSeries.size());
            timeSeriesTest.add(timeSeries.remove(index));
            testLabels.add(labels.remove(index));
        }
        LOGGER.debug("Train time series set size {}", timeSeries.size());
        LOGGER.debug("Test time series set siwe {}", timeSeriesTest.size());
        // tensor building
        INDArray[] trainDataMask = new INDArray[1];
        INDArray trainData = buildTimeSerieTensor(timeSeries, maxTimeSeriesReali, trainDataMask);
        int[] sh = trainData.shape();
        LOGGER.trace("Nb shapes : {}", sh.length);
        LOGGER.trace(" shape dims : {},{},{}", sh[0], sh[1], sh[2]);
        INDArray[] trainLabelMask = new INDArray[1];
        INDArray trainLabel = buildTimeSerieTensor(labels, maxTimeSeriesReali, trainLabelMask);

        DataSet dataSet = new DataSet(trainData, trainLabel, trainDataMask[0], trainLabelMask[0]);
        net.fit(dataSet);

        LOGGER.debug("Training stats ouput");
        evaluateDataset(net, timeSeries, labels, dataSet.numOutcomes());

        // compute test
        LOGGER.debug("Tests stats output");
        evaluateDataset(net, timeSeriesTest, testLabels, dataSet.numOutcomes());
    }

    public static LocalTime getCurTime(String time) {
        String[] vals = time.split(":");
        int hour = Integer.parseInt(vals[0]);
        int minute = Integer.parseInt(vals[1]);
        int second = Integer.parseInt(vals[2]);
        int nanoSec = Integer.parseInt(vals[3]);
        return LocalTime.of(hour, minute, second, nanoSec);
    }

    public static LocalDate getCurDate(String date) {
        String[] vals = date.split("-");
        int year = Integer.parseInt(vals[0]);
        int month = Integer.parseInt(vals[1]);
        int day = Integer.parseInt(vals[2]);
        return LocalDate.of(year, month, day);
    }

    public static boolean isTheSameTimeSerie(LocalTime lastTime, LocalDate lastDate, LocalTime curTime,
            LocalDate curDate) {
        if (lastTime == null || lastDate == null || !lastDate.equals(curDate)) {
            return false;
        }
        if ((curTime.getSecond() - lastTime.getSecond()) <= 2 && (curTime.getMinute() - lastTime.getMinute()) < 2
                && (curTime.getHour() - lastTime.getHour()) < 2) {
            return true;
        }
        return false;
    }

    public static INDArray buildNDArray(List<double[]> curTabList) {
        INDArray res = Nd4j.zeros(curTabList.size(), curTabList.get(0).length);
        for (int i = 0; i < curTabList.size(); i++) {
            res.putRow(i, Nd4j.create(curTabList.get(i)));
        }
        return res;
    }

    public static INDArray buildTimeSerieTensor(List<INDArray> timeSeries, int maxTimeSeriesReali, INDArray[] trainMask) {
        INDArray res = Nd4j.zeros(maxTimeSeriesReali, timeSeries.get(0).columns(), timeSeries.size());
        trainMask[0] = Nd4j.zeros(maxTimeSeriesReali, timeSeries.size());
        for (int k = 0; k < timeSeries.size(); k++) {
            int[] curShape = timeSeries.get(k).shape();
            for (int i = 0; i < maxTimeSeriesReali; i++) {
                if (curShape[0] <= i) {
                    break;
                }
                for (int j = 0; j < timeSeries.get(0).columns(); j++) {
                    res.put(new int[]{i, j, k}, timeSeries.get(k).getScalar(i, j));
                }
                trainMask[0].put(new int[]{i, k}, Nd4j.ones(1, 1));
            }
        }
        return res;
    }

    public static void evaluateDataset(MultiLayerNetwork net, List<INDArray> unPaddedDataset,
            List<INDArray> unPaddedlabels, int nbOutComes) {
        Evaluation eval = new Evaluation(nbOutComes);
        for (int i = 0; i < unPaddedDataset.size(); i++) {
            net.rnnClearPreviousState();
            INDArray out = net.rnnTimeStep(unPaddedDataset.get(i));
            eval.eval(unPaddedlabels.get(i), out);
        }
        LOGGER.debug(eval.stats());
    }

}
