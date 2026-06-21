package com.preanalyzer.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preanalyzer.model.ProjectModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tek dosyalık, bağımsız (internet gerektirmeyen) etkileşimli HTML rapor üretir.
 * Şablon: resources/report.html — analiz modeli JSON olarak gömülür.
 */
public class HtmlReport {

    public void write(ProjectModel model, Path outFile) throws IOException {
        String template;
        try (InputStream in = getClass().getResourceAsStream("/report.html")) {
            if (in == null) throw new IOException("report.html sablonu bulunamadi (resources)");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String json = new ObjectMapper().writeValueAsString(model);
        // </script> kaçışı: gömülü JSON script bloğunu kırmasın
        json = json.replace("</", "<\\/");
        String html = template.replace("\"__DATA_JSON__\"", json);
        Files.writeString(outFile, html, StandardCharsets.UTF_8);
        System.out.println("  HTML rapor yazildi: " + outFile.getFileName());
    }
}
