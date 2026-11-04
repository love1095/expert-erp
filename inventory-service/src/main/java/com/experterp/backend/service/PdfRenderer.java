package com.experterp.backend.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/** Prints a {@link DocumentModel} through the XHTML template {@code templates/documents/document.html} (phase D5). */
@Component
public class PdfRenderer {

    private final TemplateEngine engine = new TemplateEngine();
    private final byte[] regular;
    private final byte[] bold;

    public PdfRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/documents/");
        resolver.setSuffix(".html");
        // XML mode: the output is well-formed XHTML, which the PDF renderer needs
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        engine.setTemplateResolver(resolver);
        regular = font("fonts/LiberationSans-Regular.ttf");
        bold = font("fonts/LiberationSans-Bold.ttf");
    }

    /** The document's XHTML (also used by tests to check what is printed). */
    public String html(DocumentModel model) {
        Context ctx = new Context();
        ctx.setVariable("d", model);
        return engine.process("document", ctx);
    }

    public byte[] render(DocumentModel model) {
        return renderHtml(html(model), model.number());
    }

    /** Any other template under {@code templates/documents/}, given its model as {@code d} (statements, reminders: G3). */
    public String html(String template, Object model) {
        Context ctx = new Context();
        ctx.setVariable("d", model);
        return engine.process(template, ctx);
    }

    public byte[] render(String template, Object model, String name) {
        return renderHtml(html(template, model), name);
    }

    private byte[] renderHtml(String html, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useFont(() -> new ByteArrayInputStream(regular), "Liberation Sans", 400, FontStyle.NORMAL, true);
            b.useFont(() -> new ByteArrayInputStream(bold), "Liberation Sans", 700, FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.withProducer("Expert ERP");
            b.toStream(out);
            b.run();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not print " + name, e);
        }
        return out.toByteArray();
    }

    /** Several documents in one PDF, each starting on a new page with its own page numbers. */
    public byte[] renderAll(List<DocumentModel> models) {
        return merge(models.stream().map(this::render).toList());
    }

    /** PDFs one after the other in one file. */
    public byte[] merge(List<byte[]> pdfs) {
        if (pdfs.size() == 1) return pdfs.get(0);
        try {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] pdf : pdfs) merger.addSource(new RandomAccessReadBuffer(pdf));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merger.setDestinationStream(out);
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not combine the documents", e);
        }
    }

    private static byte[] font(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing font " + path, e);
        }
    }
}
