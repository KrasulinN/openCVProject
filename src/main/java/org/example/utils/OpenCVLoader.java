package org.example.utils;

import nu.pattern.OpenCV;

public class OpenCVLoader {
    public static boolean loadOpenCV() {
        try {
            OpenCV.loadLocally();
            System.out.println("✅ OpenCV загружен");
            return true;

        } catch (UnsatisfiedLinkError e) {
            System.out.println("❌ Ошибка загрузки OpenCV: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("❌ Ошибка: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}