package org.example;

public class TestOpenCV {
    public static void main(String[] args) {
        System.out.println("Тест OpenCV");

        if (OpenCVLoader.loadOpenCV()) {
            System.out.println("Версия: " + org.opencv.core.Core.VERSION);
        }
    }
}