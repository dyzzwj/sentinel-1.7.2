package com.dyzwj.sentinel;

import java.io.File;
import java.io.IOException;

public class Demo {

    public static void main(String[] args) {
        
        File file = new File("d:/a/b/c.txt");
        if(!file.exists()){
            File parentFile = file.getParentFile();
            if(!parentFile.exists()){
                parentFile.mkdirs();
            }
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
