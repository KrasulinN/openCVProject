package org.example;

public class OpenCVLoader {
    public static boolean loadOpenCV() {
        try {
            String dllPath = "D:\\opencv\\build\\java\\x64\\opencv_java4120.dll";
            System.load(dllPath);
            System.out.println("OpenCV загружен");
            return true;
        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage());
            return false;
        }
    }
}