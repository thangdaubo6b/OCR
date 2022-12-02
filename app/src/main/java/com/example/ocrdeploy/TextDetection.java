package com.example.ocrdeploy;

import android.app.Activity;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.TensorOperator;
import org.tensorflow.lite.support.common.TensorProcessor;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class TextDetection {
    private String model_path;
    private String model_recognition_path;
    private Interpreter tflite_Interpreter;
    private Activity activity;
    private int inputSize;
    private static final float PROBABILITY_MEAN = 128f;
    private static final float PROBABILITY_STD = 1.0f;

    private MappedByteBuffer tfliteModel;
    private int imageSizeX;
    private int imageSizeY;
    private TensorImage inputImageBuffer;
    private TensorBuffer outputProbabilityBuffer_score;
    private TensorBuffer outputProbabilityBuffer_geometry;
    private TensorProcessor probabilityProcessor;
    private TextRecognition textRecognition;


    public TextDetection(String model_path,String model_recognition_path, Activity activity, int inputSize){
        this.model_path = model_path;
        this.activity = activity;
        this.inputSize = inputSize;
        this.model_recognition_path = model_recognition_path;
    }

    public void init() throws IOException{
        loadModel();
        textRecognition = new TextRecognition(model_recognition_path, activity);
        textRecognition.init();
    }

    private void loadModel() throws IOException {
        tfliteModel = FileUtil.loadMappedFile(activity, model_path);
        // Create a TFLITE interpreter instance
        tflite_Interpreter = new Interpreter(tfliteModel);

        // Read type and shape of input and output tensors, respectively
        int imageTensorIndex = 0;
        int[] imageShape = tflite_Interpreter.getInputTensor(imageTensorIndex).shape();
        imageSizeY = imageShape[1];
        imageSizeX = imageShape[2];

        DataType imageDataType = tflite_Interpreter.getInputTensor(imageTensorIndex).dataType();

        // Hava 2 Out put Score And Geometry
        int probabilityTensorIndex_1 = 0;
        int probabilityTensorIndex_2 = 1;

        int[] probabilityShape_1 = tflite_Interpreter.getOutputTensor(probabilityTensorIndex_1).shape();//{1, num_class}
        int[] probabilityShape_2 = tflite_Interpreter.getOutputTensor(probabilityTensorIndex_2).shape();

        DataType probabilityDataType_1 = tflite_Interpreter.getOutputTensor(probabilityTensorIndex_1).dataType();
        DataType probabilityDataType_2 = tflite_Interpreter.getOutputTensor(probabilityTensorIndex_2).dataType();
        // Creates the input tensor
        inputImageBuffer = new TensorImage(imageDataType);

        // Creates The Output Tensor And Its Processor
        outputProbabilityBuffer_score = TensorBuffer.createFixedSize(probabilityShape_1, probabilityDataType_1);
        outputProbabilityBuffer_geometry = TensorBuffer.createFixedSize(probabilityShape_2, probabilityDataType_2);

//        outputProbabilityBuffer_score = TensorBuffer.createFixedSize(new int[]{1, 1, 80 , 80 }, probabilityDataType_1);
//        outputProbabilityBuffer_geometry = TensorBuffer.createFixedSize(new int[]{1, 5, 80, 80}, probabilityDataType_2);

        probabilityProcessor = new TensorProcessor.Builder().add(getPostprocessNormalizeOp()).build();

//        1,80,80,1
//        1,80,80,5
    }

    private TensorOperator getPostprocessNormalizeOp(){
        return new NormalizeOp(PROBABILITY_MEAN, PROBABILITY_STD);
    }

    public Bitmap detections(Bitmap bitmap, int sensorOrientation){
        Mat matImg = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(bitmap, matImg);


        inputImageBuffer = loadImage(bitmap, sensorOrientation);

        float w_ratio = bitmap.getWidth()/320f;
        float h_ratio = bitmap.getHeight()/320f;

        HashMap<Integer, Object> detectionOuput = new HashMap<Integer, Object>();
        Object[] detectionInput = new Object[]{inputImageBuffer.getBuffer()};

        detectionOuput.put(0, outputProbabilityBuffer_score.getBuffer().rewind());
        detectionOuput.put(1, outputProbabilityBuffer_geometry.getBuffer().rewind());

        tflite_Interpreter.runForMultipleInputsOutputs(detectionInput, detectionOuput);

        float[] geometry = outputProbabilityBuffer_geometry.getFloatArray();
        float[] score = outputProbabilityBuffer_score.getFloatArray();

        ArrayList<List> box_extract = box_extractor(score, new int[]{1,1,80,80},geometry, new int[]{1, 5, 80,80}, 0.50f);
        ArrayList<Float> confidences = (ArrayList<Float>) box_extract.get(1);
        ArrayList<List> rectangles = (ArrayList<List>) box_extract.get(0);



        ArrayList<List> final_rectangle = non_max_suppression(rectangles, confidences, 0.3f);

        int count_index = 0;
        for (List<Integer> rect: final_rectangle) {
            int x1 = (int)(rect.get(0) * w_ratio);
            int y1 = (int) (h_ratio * rect.get(1));
            int x2 = (int)(w_ratio * rect.get(2));
            int y2 = (int)(h_ratio * rect.get(3));
            x1 = x1 > bitmap.getWidth() ? bitmap.getWidth() : x1;
            x2 = x2 > bitmap.getWidth() ? bitmap.getWidth() : x2;
            y1 = y1 > bitmap.getHeight() ? bitmap.getHeight() : y1;
            y2 = y2 > bitmap.getHeight() ? bitmap.getHeight() : y2;


            Mat matCropImage = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3);
            Utils.bitmapToMat(bitmap, matCropImage);
            Rect roi = new Rect(new Point(x1, y1),new Point(x2, y2));
            Mat cropped = new Mat(matCropImage, roi);
            Bitmap new_cropBitmap = Bitmap.createBitmap(cropped.cols(),cropped.rows(), Bitmap.Config.ARGB_8888);
            Imgproc.cvtColor(cropped, cropped, Imgproc.COLOR_RGBA2GRAY);
            Utils.matToBitmap(cropped, new_cropBitmap);
//
//            Bitmap cropted_cropBitmap = Bitmap.createBitmap(matCropImage.cols(),matCropImage.rows(), Bitmap.Config.ARGB_8888);
//            Utils.matToBitmap(matCropImage, cropted_cropBitmap);

            String text_recog = textRecognition.recognitions(new_cropBitmap, 0);
            if (text_recog.length() > 0)
            {
                Imgproc.putText(matImg, text_recog , new Point(x1, y1), 1, 2, new Scalar(100,0, 0, 255), 1);
            }

            Imgproc.rectangle(matImg, new Point(x1,y1), new Point(x2,y2), new Scalar(100 ,0, 255, 255), 2);
            count_index ++;
        }

        Utils.matToBitmap(matImg, bitmap);

        return bitmap;
    }

    private ArrayList<List> box_extractor(@NonNull float[] score, @NonNull int[] score_shape, @NonNull float[] geometry, @NonNull int[] geometry_shape, @NonNull float min_confidence) {
        int num_rows = score_shape[2];
        int num_cols = score_shape[3];
        // Reshape To Score(1,1,80,80) / Geometry(1,5,80,80);
        // Reshape To Score(1,80,80) / Geometry(5,80,80);
        ArrayList<List> score_reshape = resize_box(score, score_shape);
        ArrayList<List> geometry_reshape = resize_box(geometry, geometry_shape);
        //
        ArrayList<List> rectangles = new ArrayList<List>();
        ArrayList<Float> confidences = new ArrayList<Float>();

        for (int x = 1; x <= num_rows; x = x + 1) {
            List<Float> score_data = (List<Float>) (score_reshape.get(0)).get(x-1);
            List<Float> x_data0 = (List<Float>) (geometry_reshape.get(0)).get(x-1);
            List<Float> x_data1 = (List<Float>) (geometry_reshape.get(1)).get(x-1);
            List<Float> x_data2 = (List<Float>) (geometry_reshape.get(2)).get(x-1);
            List<Float> x_data3 = (List<Float>) (geometry_reshape.get(3)).get(x-1);
            List<Float> angles_data = (List<Float>) (geometry_reshape.get(4)).get(x-1);

            for (int y = 1; y <= num_cols; y = y + 1) {
                if (score_data.get(y-1) < min_confidence)
                    continue;

                float offset_x = (float) ((y-1) * 4.0);
                float offset_y = (float) ((x-1) * 4.0);

                float box_h = x_data0.get(y-1) + x_data2.get(y-1);
                float box_w = x_data1.get(y-1) + x_data3.get(y-1);

                float angle = angles_data.get(y-1);
                Double cos = Math.cos(angle);
                Double sin = Math.sin(angle);

                int end_x = (int) (offset_x + (cos * x_data1.get(y-1)) + (sin * x_data2.get(y-1)));
                int end_y = (int) (offset_y + (cos * x_data2.get(y-1)) - (sin * x_data1.get(y-1)));
                int start_x = (int)(end_x - box_w);
                int start_y = (int)(end_y - box_h);

                rectangles.add(new ArrayList<Integer>(Arrays.asList(start_x, start_y, end_x, end_y)));
                confidences.add(score_data.get(y-1));
            }
        }
        return new ArrayList<List>(Arrays.asList(rectangles, confidences));
    }

    private ArrayList<List> non_max_suppression(ArrayList<List> rectangle, ArrayList<Float> confideces, float overLapThresh){
        ArrayList<List> final_box_detection = new ArrayList<List>();
        while (confideces.size() > 0){
            int index = confideces.indexOf(Collections.max(confideces));
            ArrayList<Integer> list_index = new ArrayList<Integer>();
            List<Integer> box_current = rectangle.get(index);
            final_box_detection.add(box_current);
            rectangle.remove(index);
            confideces.remove(index);
            int start_index = 0;
            while (start_index < confideces.size()){
                if (IOU(box_current, rectangle.get(start_index), overLapThresh)){
                    confideces.remove(start_index);
                    rectangle.remove(start_index);
                }
                else{
                    start_index ++;
                }
            }
        }
        return final_box_detection;
    }

    private boolean IOU(List<Integer> box1, List<Integer> box2, float overlap_threshold){
        int x1, x2, x3, x4;
        int y1, y2, y3, y4;
        x1 = box1.get(0);
        y1 = box1.get(1);
        x2 = box1.get(2);
        y2 = box1.get(3);
        x3 = box2.get(0);
        y3 = box2.get(1);
        x4 = box2.get(2);
        y4 = box2.get(3);

        int area1 = (x2 - x1) * (y2 - y1);
        int area2 = (x4 - x3) * (y4 - y3);

        int x1_iou = x3 > x1 ? x3 : x1;
        int y1_iou = y3 > y1 ? y3 : y1;
        int x2_iou = x2 < x4 ? x2 : x4;
        int y2_iou = y2 < y4 ? y2 : y4;

        int w_iou = 0 > (x2_iou - x1_iou) ? 0 : (x2_iou - x1_iou);
        int h_iou = 0 > (y2_iou - y1_iou) ? 0 : (y2_iou - y1_iou);

        int intersection_arae = h_iou * w_iou;

        int union_area = area1 + area2 - intersection_arae;

        return (intersection_arae * 1.0 / union_area) >= overlap_threshold ? true : false;
    }

    private ArrayList<List> resize_box(@NonNull float[] box, @NonNull int [] box_shape){
        ArrayList<List> box_reshape = new ArrayList<List>();
        for (int x = 1; x <= box_shape[1]; x = x + 1){
            int count = 0;
            ArrayList<List> box_reshape_sub1 = new ArrayList<List>();
            for (int y = 1; y <= box_shape[2]; y = y + 1){
                ArrayList<Float> box_sub2 = new ArrayList<Float>();
                for (int z = 1 ; z <= box_shape[3]; z = z + 1){
                    box_sub2.add(box[x - 1 + box_shape[1] * count]);
                    count++;
                }
                box_reshape_sub1.add(box_sub2);
            }
            box_reshape.add(box_reshape_sub1);
        }
        return box_reshape;
    }

    private TensorImage loadImage(Bitmap bitmap, int senorOrientation){
        inputImageBuffer.load(bitmap);
        int cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int numRoration = senorOrientation / 90;
        // Define an ImageProcessor From TFlite to do preprocessing
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(new Rot90Op(numRoration))
                .add(getPostprocessNormalizeOp())
                .build();

        return imageProcessor.process(inputImageBuffer);
    }
}
