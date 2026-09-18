package com.munsif;

import com.munsif.infrastructure.pptx.PptxPresentationWriter;
import com.munsif.infrastructure.telegram.TelegramExportReader;
import com.munsif.usecase.GeneratePresentationUseCase;
import com.munsif.usecase.port.ExportReader;
import com.munsif.usecase.port.PresentationExporter;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.time.LocalDate;

/**
 * المنصف في القدرات — نقطة الدخول وواجهة JavaFX.
 * الكلاس ده "composition root": بيوصّل الـ adapters الفعلية (قراءة تليجرام،
 * كتابة PowerPoint) بالحالة الاستخدامية، وبعد كده شغله الوحيد إنه ياخد
 * مدخلات المستخدم ويعرض النتيجة — من غير أي منطق عمل جوّاه.
 */
public class MainApp extends Application {

    private final ExportReader reader = new TelegramExportReader();
    private final PresentationExporter exporter = new PptxPresentationWriter();
    private final GeneratePresentationUseCase useCase = new GeneratePresentationUseCase(reader, exporter);

    private File exportDir;
    private Label pathLabel;
    private DatePicker fromPicker;
    private DatePicker toPicker;
    private Spinner<Integer> gapSpinner;
    private TextArea logArea;
    private Button generateBtn;
    private Button openBtn;
    private File lastOutput;

    @Override
    public void start(Stage stage) {
        stage.setTitle("المنصف في القدرات");

        Label title = new Label("المنصف في القدرات");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        Label subtitle = new Label("تحويل محادثة تليجرام إلى عرض PowerPoint بدون أسماء");
        subtitle.setStyle("-fx-text-fill: #555;");

        Button chooseBtn = new Button("اختيار مجلد التصدير...");
        pathLabel = new Label("لم يتم اختيار مجلد بعد");
        pathLabel.setWrapText(true);
        chooseBtn.setOnAction(e -> chooseFolder(stage));

        fromPicker = new DatePicker();
        toPicker = new DatePicker(LocalDate.now());

        VBox fromBox = new VBox(4, new Label("من تاريخ (اختياري)"), fromPicker);
        VBox toBox = new VBox(4, new Label("إلى تاريخ (اختياري)"), toPicker);
        HBox dateBox = new HBox(16, fromBox, toBox);

        gapSpinner = new Spinner<>(1, 3600, 30);
        gapSpinner.setEditable(true);
        VBox gapBox = new VBox(4, new Label("فجوة تجميع الرسائل (بالثواني)"), gapSpinner);

        generateBtn = new Button("توليد العرض التقديمي");
        generateBtn.setDisable(true);
        generateBtn.setMaxWidth(Double.MAX_VALUE);
        generateBtn.setOnAction(e -> runGeneration());

        openBtn = new Button("فتح الملف الناتج");
        openBtn.setDisable(true);
        openBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setOnAction(e -> openOutput());

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox root = new VBox(14,
                title, subtitle,
                new Separator(),
                chooseBtn, pathLabel,
                dateBox,
                gapBox,
                generateBtn,
                openBtn,
                new Label("سجل التنفيذ:"),
                logArea
        );
        root.setPadding(new Insets(20));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        stage.setScene(new Scene(root, 600, 640));
        stage.show();
    }

    private void chooseFolder(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("اختاري مجلد تصدير Telegram (اللي فيه result.json)");
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            exportDir = chosen;
            pathLabel.setText(chosen.getAbsolutePath());
            generateBtn.setDisable(false);
        }
    }

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }

    private void runGeneration() {
        final File dir = exportDir;
        final LocalDate fromDate = fromPicker.getValue();
        final LocalDate toDate = toPicker.getValue();
        final int gapSeconds = gapSpinner.getValue();

        generateBtn.setDisable(true);
        openBtn.setDisable(true);
        logArea.clear();

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                return useCase.execute(dir, fromDate, toDate, gapSeconds, MainApp.this::log);
            }
        };

        task.setOnSucceeded(e -> {
            lastOutput = task.getValue();
            generateBtn.setDisable(false);
            openBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            log("حصل خطأ: " + (ex != null ? ex.getMessage() : "غير معروف"));
            generateBtn.setDisable(false);
        });

        Thread t = new Thread(task, "munsif-generate");
        t.setDaemon(true);
        t.start();
    }

    private void openOutput() {
        if (lastOutput != null && lastOutput.exists()) {
            try {
                Desktop.getDesktop().open(lastOutput);
            } catch (Exception e) {
                log("تعذر فتح الملف: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
