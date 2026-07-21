package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedImage;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfBoxImageExtractor implements PdfImageExtractor {
    @Override
    public List<ParsedImage> extract(PDDocument document) throws IOException {
        List<ParsedImage> images = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            extractFromResources(page.getResources(), pageIndex + 1, images, "page");
        }
        return images;
    }

    private void extractFromResources(PDResources resources, int pageNo, List<ParsedImage> images, String position) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                int index = (int) images.stream().filter(existing -> existing.pageNo() == pageNo).count();
                images.add(new ParsedImage(
                        pageNo,
                        index,
                        name.getName(),
                        image.getSuffix(),
                        image.getWidth(),
                        image.getHeight(),
                        position,
                        ""
                ));
            } else if (xObject instanceof PDFormXObject form) {
                extractFromResources(form.getResources(), pageNo, images, "form:" + name.getName());
            }
        }
    }
}
