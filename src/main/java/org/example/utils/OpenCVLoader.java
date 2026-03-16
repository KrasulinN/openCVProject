package org.example.utils;

public class OpenCVLoader {
    public static boolean loadOpenCV() {
        try {
            // ⚠️ КАЖДЫЙ СТУДЕНТ ДОЛЖЕН ИСПРАВИТЬ ПУТЬ ПОД СЕБЯ!
            String dllPath = "D:\\opencv\\build\\java\\x64\\opencv_java4120.dll";

            System.load(dllPath);
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