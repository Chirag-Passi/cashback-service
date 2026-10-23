package com.js.motos.clientApp.admin.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.js.motos.clientApp.common.PageResponse;
import com.js.motos.clientApp.qr.domain.QrCode;
import com.js.motos.clientApp.qr.service.QrCodeService;
import com.js.motos.clientApp.qr.service.QrImageService;
import com.js.motos.clientApp.qr.web.dto.QrDtos;
import com.js.motos.clientApp.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1/admin/qr")
@Tag(name = "Admin — QR codes", description = "Minting print runs of cashback codes")
public class AdminQrController {

    private final QrCodeService qrCodeService;
    private final QrImageService qrImageService;

    public AdminQrController(QrCodeService qrCodeService, QrImageService qrImageService) {
        this.qrCodeService = qrCodeService;
        this.qrImageService = qrImageService;
    }

    // --- batches ----------------------------------------------------------

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a batch of codes at a fixed amount",
            description = "Mints `quantity` single-use codes, each worth `amount`. "
                    + "Download the images as a ZIP to send to the printer.")
    public QrDtos.BatchSummary createBatch(AuthPrincipal principal,
                                           @Valid @RequestBody QrDtos.CreateBatch request) {
        return qrCodeService.createBatch(principal.userId(), request);
    }

    @GetMapping("/batches")
    @Operation(summary = "List batches with redemption stats")
    public PageResponse<QrDtos.BatchSummary> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return PageResponse.from(qrCodeService.listBatches(pageable), batch -> batch);
    }

    @GetMapping("/batches/{batchId}")
    @Operation(summary = "Get one batch")
    public QrDtos.BatchSummary getBatch(@PathVariable UUID batchId) {
        return qrCodeService.getBatch(batchId);
    }

    @GetMapping("/batches/{batchId}/codes")
    @Operation(summary = "List the codes in a batch")
    public PageResponse<QrDtos.QrCodeItem> listCodes(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        return PageResponse.from(qrCodeService.listCodes(batchId, pageable), code -> code);
    }

    // --- print artefacts --------------------------------------------------

    @GetMapping(value = "/batches/{batchId}/images.zip", produces = "application/zip")
    @Operation(summary = "Download every QR image in the batch as a ZIP",
            description = "One PNG per code, named after the code. Hand this to the printer.")
    public ResponseEntity<byte[]> downloadBatchImages(@PathVariable UUID batchId) {
        List<QrCode> codes = qrCodeService.allCodesInBatch(batchId);
        byte[] zip = qrImageService.toZip(codes, qrCodeService::payloadFor);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"qr-batch-" + batchId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    @GetMapping(value = "/batches/{batchId}/export.csv", produces = "text/csv")
    @Operation(summary = "Download the batch as CSV",
            description = "Columns: code, payload, amount, status, expires_at. "
                    + "Useful for mail-merge onto printed stationery.")
    public ResponseEntity<byte[]> exportBatchCsv(@PathVariable UUID batchId) {
        List<QrCode> codes = qrCodeService.allCodesInBatch(batchId);

        StringBuilder csv = new StringBuilder("code,payload,amount,status,expires_at\n");
        for (QrCode code : codes) {
            csv.append(code.getCode()).append(',')
                    .append(qrCodeService.payloadFor(code)).append(',')
                    .append(code.getAmount().toPlainString()).append(',')
                    .append(code.getStatus()).append(',')
                    .append(code.getExpiresAt() == null ? "" : code.getExpiresAt())
                    .append('\n');
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"qr-batch-" + batchId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/codes/{code}/image.png", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a single code as a PNG")
    public ResponseEntity<byte[]> codeImage(@PathVariable String code,
                                            @RequestParam(defaultValue = "512") int size) {
        QrDtos.QrCodeItem item = qrCodeService.getByCode(code);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(qrImageService.toPng(item.payload(), size));
    }

    // --- voiding ----------------------------------------------------------

    @PostMapping("/codes/{codeId}/void")
    @Operation(summary = "Void a single unclaimed code")
    public QrDtos.QrCodeItem voidCode(AuthPrincipal principal,
                                      @PathVariable UUID codeId,
                                      @Valid @RequestBody QrDtos.VoidRequest request) {
        return qrCodeService.voidCode(principal.userId(), codeId, request.reason());
    }

    @PostMapping("/batches/{batchId}/void")
    @Operation(summary = "Void every unclaimed code in a batch",
            description = "For a lost or misprinted print run. Already-claimed codes are untouched.")
    public Map<String, Object> voidBatch(AuthPrincipal principal,
                                         @PathVariable UUID batchId,
                                         @Valid @RequestBody QrDtos.VoidRequest request) {
        int voided = qrCodeService.voidBatch(principal.userId(), batchId, request.reason());
        return Map.of("codesVoided", voided);
    }
}