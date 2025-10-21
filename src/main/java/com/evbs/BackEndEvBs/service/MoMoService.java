package com.evbs.BackEndEvBs.service;

import com.evbs.BackEndEvBs.config.MoMoConfig;
import com.evbs.BackEndEvBs.entity.DriverSubscription;
import com.evbs.BackEndEvBs.entity.Payment;
import com.evbs.BackEndEvBs.entity.ServicePackage;
import com.evbs.BackEndEvBs.entity.User;
import com.evbs.BackEndEvBs.exception.exceptions.AuthenticationException;
import com.evbs.BackEndEvBs.exception.exceptions.NotFoundException;
import com.evbs.BackEndEvBs.repository.DriverSubscriptionRepository;
import com.evbs.BackEndEvBs.repository.PaymentRepository;
import com.evbs.BackEndEvBs.repository.ServicePackageRepository;
import com.evbs.BackEndEvBs.util.MoMoUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MoMoService {

    @Autowired
    private final MoMoConfig moMoConfig;

    @Autowired
    private final ServicePackageRepository servicePackageRepository;

    @Autowired
    private final PaymentRepository paymentRepository;

    @Autowired
    private final DriverSubscriptionService driverSubscriptionService;

    @Autowired
    private final DriverSubscriptionRepository driverSubscriptionRepository;

    @Autowired
    private final AuthenticationService authenticationService;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * TẠO MOMO PAYMENT URL CHO GÓI DỊCH VỤ
     * 
     * WORKFLOW:
     * BUOC 1: Driver chọn gói (packageId) → Gọi API này
     * BUOC 2: System tạo MoMo payment URL
     * BUOC 3: Driver redirect đến MoMo app/website
     * BUOC 4: Driver thanh toán
     * BUOC 5: MoMo callback về redirectUrl (từ frontend hoặc config)
     * BUOC 6: System TẠO subscription ACTIVE tự động
     * 
     * QUAN TRỌNG: Lưu driverId vào extraData vì callback KHÔNG CÓ TOKEN!
     * 
     * @param packageId ID của service package muốn mua
     * @param customRedirectUrl URL redirect tùy chỉnh từ frontend (có thể null)
     * @return Map chứa paymentUrl để redirect driver
     */
    public Map<String, String> createPaymentUrl(Long packageId, String customRedirectUrl) {
        // BUOC 1: Validate service package tồn tại
        ServicePackage servicePackage = servicePackageRepository.findById(packageId)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy gói dịch vụ với ID: " + packageId));

        // BUOC 2: Kiểm tra driver có gói active và còn lượt swap không
        User currentDriver = authenticationService.getCurrentUser();
        var activeSubscriptionOpt = driverSubscriptionRepository.findActiveSubscriptionByDriver(
                currentDriver,
                java.time.LocalDate.now()
        );

        if (activeSubscriptionOpt.isPresent()) {
            DriverSubscription existingSub = activeSubscriptionOpt.get();

            if (existingSub.getRemainingSwaps() > 0) {
                throw new AuthenticationException(
                        "Bạn đã có gói dịch vụ ACTIVE và còn " + existingSub.getRemainingSwaps() + " lượt swap! " +
                                "Vui lòng sử dụng hết lượt swap hiện tại trước khi mua gói mới."
                );
            }

            log.info("Driver {} có gói active nhưng hết lượt swap. Cho phép mua gói mới...",
                    currentDriver.getEmail());
        }

        // BUOC 3: Chuẩn bị thông tin thanh toán MoMo
        String orderId = MoMoUtil.generateOrderId();
        String requestId = MoMoUtil.generateRequestId();
        long amount = servicePackage.getPrice().longValue();
        
        // LƯU DRIVER ID vào extraData vì callback không có token!
        String extraData = "packageId=" + packageId + "&driverId=" + currentDriver.getId();

        // Xác định redirectUrl: dùng từ frontend nếu có, không thì dùng config
        String finalRedirectUrl = (customRedirectUrl != null && !customRedirectUrl.trim().isEmpty()) 
                ? customRedirectUrl 
                : moMoConfig.getRedirectUrl();
        
        log.info("Using redirect URL: {}", finalRedirectUrl);

        // Parameters for signature (sorted by key)
        Map<String, String> signatureParams = new LinkedHashMap<>();
        signatureParams.put("accessKey", moMoConfig.getAccessKey());
        signatureParams.put("amount", String.valueOf(amount));
        signatureParams.put("extraData", extraData);
        signatureParams.put("ipnUrl", moMoConfig.getIpnUrl());
        signatureParams.put("orderId", orderId);
        signatureParams.put("orderInfo", "Thanh toan goi dich vu: " + servicePackage.getName());
        signatureParams.put("partnerCode", moMoConfig.getPartnerCode());
        signatureParams.put("redirectUrl", finalRedirectUrl);
        signatureParams.put("requestId", requestId);
        signatureParams.put("requestType", moMoConfig.getRequestType());

        // Build raw signature string
        String rawSignature = MoMoUtil.buildRawSignature(signatureParams);
        String signature = MoMoUtil.hmacSHA256(rawSignature, moMoConfig.getSecretKey());

        // BUOC 4: Build request body gửi đến MoMo
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("partnerCode", moMoConfig.getPartnerCode());
        requestBody.put("partnerName", "EVBattery Swap System");
        requestBody.put("storeId", "EVBatteryStore");
        requestBody.put("requestId", requestId);
        requestBody.put("amount", amount);
        requestBody.put("orderId", orderId);
        requestBody.put("orderInfo", "Thanh toan goi dich vu: " + servicePackage.getName());
        requestBody.put("redirectUrl", finalRedirectUrl); // Dùng redirectUrl từ frontend hoặc config
        requestBody.put("ipnUrl", moMoConfig.getIpnUrl());
        requestBody.put("lang", "vi");
        requestBody.put("extraData", extraData); // Dùng extraData có cả packageId và driverId
        requestBody.put("requestType", moMoConfig.getRequestType());
        requestBody.put("signature", signature);

        // BUOC 5: Gọi MoMo API
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    moMoConfig.getEndpoint(),
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && responseBody.get("resultCode").equals(0)) {
                String payUrl = (String) responseBody.get("payUrl");

                log.info("MoMo payment URL created for package {}: {} - {} VND",
                        packageId, servicePackage.getName(), amount);

                Map<String, String> result = new HashMap<>();
                result.put("paymentUrl", payUrl);
                result.put("orderId", orderId);
                result.put("requestId", requestId);
                result.put("message", "Redirect user to this URL to complete payment");

                return result;
            } else {
                throw new RuntimeException("MoMo API error: " + responseBody);
            }

        } catch (Exception e) {
            log.error("Lỗi tạo MoMo payment: {}", e.getMessage());
            throw new RuntimeException("Không thể tạo MoMo payment URL", e);
        }
    }

    /**
     * XỬ LÝ CALLBACK TỪ MOMO SAU KHI THANH TOÁN
     * 
     * WORKFLOW:
     * BUOC 1: MoMo gửi callback với thông tin thanh toán
     * BUOC 2: System verify signature để đảm bảo request từ MoMo thật
     * BUOC 3: Nếu thanh toán THÀNH CÔNG (resultCode = 0):
     *    - Tạo Payment record
     *    - Tạo DriverSubscription ACTIVE tự động
     *    - Driver có thể swap miễn phí ngay lập tức
     * BUOC 4: Nếu thanh toán THẤT BẠI:
     *    - KHÔNG tạo subscription
     *    - Trả về thông báo lỗi
     * 
     * QUAN TRỌNG: Callback KHÔNG CÓ TOKEN nên lấy driverId từ extraData!
     * 
     * @param request HttpServletRequest chứa callback params từ MoMo
     * @return Map chứa kết quả xử lý (success/error)
     */
    @Transactional
    public Map<String, Object> handleMoMoReturn(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        try {
            // BUOC 1: Lấy parameters từ MoMo callback
            String partnerCode = request.getParameter("partnerCode");
            String orderId = request.getParameter("orderId");
            String requestId = request.getParameter("requestId");
            String amount = request.getParameter("amount");
            String orderInfo = request.getParameter("orderInfo");
            String orderType = request.getParameter("orderType");
            String transId = request.getParameter("transId");
            String resultCode = request.getParameter("resultCode");
            String message = request.getParameter("message");
            String payType = request.getParameter("payType");
            String responseTime = request.getParameter("responseTime");
            String extraData = request.getParameter("extraData");
            String signature = request.getParameter("signature");

            log.info("Nhận callback từ MoMo: orderId={}, resultCode={}, message={}",
                    orderId, resultCode, message);

            // BUOC 2: Verify signature để đảm bảo request từ MoMo thật
            Map<String, String> signatureParams = new LinkedHashMap<>();
            signatureParams.put("accessKey", moMoConfig.getAccessKey());
            signatureParams.put("amount", amount);
            signatureParams.put("extraData", extraData != null ? extraData : "");
            signatureParams.put("message", message);
            signatureParams.put("orderId", orderId);
            signatureParams.put("orderInfo", orderInfo);
            signatureParams.put("orderType", orderType);
            signatureParams.put("partnerCode", partnerCode);
            signatureParams.put("payType", payType);
            signatureParams.put("requestId", requestId);
            signatureParams.put("responseTime", responseTime);
            signatureParams.put("resultCode", resultCode);
            signatureParams.put("transId", transId);

            String rawSignature = MoMoUtil.buildRawSignature(signatureParams);
            String calculatedSignature = MoMoUtil.hmacSHA256(rawSignature, moMoConfig.getSecretKey());

            if (!calculatedSignature.equals(signature)) {
                throw new SecurityException("Chữ ký MoMo không hợp lệ! Có thể bị giả mạo.");
            }

            log.info("Signature hợp lệ - Request từ MoMo thật");

            // BUOC 3: Lấy packageId và driverId từ extraData
            // Format: "packageId=1&driverId=13"
            Map<String, String> extraDataMap = parseExtraData(extraData);
            Long packageId = extractLong(extraDataMap, "packageId");
            Long driverId = extractLong(extraDataMap, "driverId");
            
            if (packageId == null || driverId == null) {
                throw new RuntimeException("Không thể lấy packageId hoặc driverId từ extraData: " + extraData);
            }

            ServicePackage servicePackage = servicePackageRepository.findById(packageId)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy gói dịch vụ ID: " + packageId));

            // BUOC 4: Xử lý kết quả thanh toán
            if ("0".equals(resultCode)) {
                // THANH TOÁN THÀNH CÔNG
                log.info("Thanh toán MoMo thành công: orderId={}, transId={}, driverId={}", orderId, transId, driverId);

                // Tạo subscription tự động (dùng overload method vì KHÔNG CÓ TOKEN)
                DriverSubscription subscription = driverSubscriptionService.createSubscriptionAfterPayment(packageId, driverId);

                // Lưu Payment record
                Payment payment = new Payment();
                payment.setSubscription(subscription);
                payment.setAmount(new BigDecimal(amount));
                payment.setPaymentMethod("MOMO");
                payment.setPaymentDate(LocalDateTime.now());
                payment.setStatus(Payment.Status.COMPLETED);
                paymentRepository.save(payment);

                log.info("Đã lưu Payment và tạo Subscription ID: {}", subscription.getId());

                result.put("success", true);
                result.put("message", "Thanh toán thành công! Gói dịch vụ đã được kích hoạt.");
                result.put("subscriptionId", subscription.getId());
                result.put("packageName", servicePackage.getName());
                result.put("maxSwaps", servicePackage.getMaxSwaps());
                result.put("remainingSwaps", subscription.getRemainingSwaps());
                result.put("startDate", subscription.getStartDate().toString());
                result.put("endDate", subscription.getEndDate().toString());
                result.put("amount", amount);
                result.put("transactionCode", transId);

            } else {
                // THANH TOÁN THẤT BẠI
                log.warn("Thanh toán MoMo thất bại: orderId={}, resultCode={}, message={}",
                        orderId, resultCode, message);

                result.put("success", false);
                result.put("message", "Thanh toán thất bại: " + message);
                result.put("resultCode", resultCode);
            }

        } catch (Exception e) {
            log.error("Lỗi xử lý callback MoMo: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "Lỗi xử lý thanh toán: " + e.getMessage());
        }

        return result;
    }

    // HELPER METHODS

    /**
     * PARSE EXTRADATA THÀNH MAP
     * 
     * Chuyển string "packageId=1&driverId=13" thành Map:
     * {
     *   "packageId": "1",
     *   "driverId": "13"
     * }
     * 
     * @param extraData String dạng "key1=value1&key2=value2"
     * @return Map<String, String>
     */
    private Map<String, String> parseExtraData(String extraData) {
        Map<String, String> result = new HashMap<>();
        if (extraData != null && !extraData.isEmpty()) {
            String[] pairs = extraData.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=");
                if (keyValue.length == 2) {
                    result.put(keyValue[0], keyValue[1]);
                }
            }
        }
        return result;
    }

    /**
     * LẤY GIÁ TRỊ LONG TỪ MAP
     * 
     * Lấy value từ map và parse thành Long
     * Nếu không parse được thì return null
     * 
     * @param map Map chứa data
     * @param key Key cần lấy
     * @return Long value hoặc null nếu không hợp lệ
     */
    private Long extractLong(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.error("Giá trị Long không hợp lệ cho key {}: {}", key, value);
            }
        }
        return null;
    }
}
