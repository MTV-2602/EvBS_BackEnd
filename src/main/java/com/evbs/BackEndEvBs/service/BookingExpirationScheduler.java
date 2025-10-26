package com.evbs.BackEndEvBs.service;

import com.evbs.BackEndEvBs.entity.Battery;
import com.evbs.BackEndEvBs.entity.Booking;
import com.evbs.BackEndEvBs.entity.DriverSubscription;
import com.evbs.BackEndEvBs.model.EmailDetail;
import com.evbs.BackEndEvBs.repository.BatteryRepository;
import com.evbs.BackEndEvBs.repository.BookingRepository;
import com.evbs.BackEndEvBs.repository.DriverSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SCHEDULED TASK - AUTO-CANCEL BOOKING HET HAN
 */
@Service
@RequiredArgsConstructor
public class BookingExpirationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BookingExpirationScheduler.class);

    private final BatteryRepository batteryRepository;
    private final BookingRepository bookingRepository;
    private final DriverSubscriptionRepository driverSubscriptionRepository;
    private final EmailService emailService;

    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void cancelExpiredBookings() {
        LocalDateTime now = LocalDateTime.now();

        List<Battery> expiredBatteries = batteryRepository.findAll()
                .stream()
                .filter(b -> b.getStatus() == Battery.Status.PENDING
                        && b.getReservationExpiry() != null
                        && b.getReservationExpiry().isBefore(now))
                .toList();

        if (expiredBatteries.isEmpty()) {
            logger.debug("Khong co booking nao het han luc: {}", now);
            return;
        }

        logger.info("Tim thay {} pin PENDING het han reservation. Bat dau huy booking...", expiredBatteries.size());

        int cancelledCount = 0;

        for (Battery battery : expiredBatteries) {
            try {
                Booking booking = battery.getReservedForBooking();

                if (booking == null) {
                    logger.warn("Pin PENDING nhung khong co booking lien ket. BatteryID: {}", battery.getId());
                    releaseBattery(battery);
                    continue;
                }

                if (booking.getStatus() == Booking.Status.CONFIRMED) {
                    // TRU LUOT SWAP VI DRIVER KHONG DEN
                    deductSwapForNoShow(booking);

                    booking.setStatus(Booking.Status.CANCELLED);
                    booking.setReservedBattery(null);
                    booking.setReservationExpiry(null);
                    bookingRepository.save(booking);

                    logger.info("Da huy booking het han VA TRU LUOT SWAP. BookingID: {}, ConfirmationCode: {}, DriverID: {}",
                            booking.getId(), booking.getConfirmationCode(), booking.getDriver().getId());
                    cancelledCount++;

                    // GỬI EMAIL THÔNG BÁO HỦY TỰ ĐỘNG CHO DRIVER
                    sendAutoCancellationEmail(booking);
                }

                releaseBattery(battery);

            } catch (Exception e) {
                logger.error("Loi khi huy booking het han. BatteryID: {}", battery.getId(), e);
            }
        }

        logger.info("Hoan thanh xu ly booking het han. So luong huy: {}/{}", cancelledCount, expiredBatteries.size());
    }

    private void releaseBattery(Battery battery) {
        battery.setStatus(Battery.Status.AVAILABLE);
        battery.setReservedForBooking(null);
        battery.setReservationExpiry(null);
        batteryRepository.save(battery);

        logger.debug("Da giai phong pin. BatteryID: {}, StationID: {}",
                battery.getId(), battery.getCurrentStation() != null ? battery.getCurrentStation().getId() : null);
    }

    private void deductSwapForNoShow(Booking booking) {
        try {
            List<DriverSubscription> activeSubscriptions = driverSubscriptionRepository
                    .findActiveSubscriptionsByDriver(booking.getDriver(), java.time.LocalDate.now());

            if (activeSubscriptions.isEmpty()) {
                logger.warn("Khong tim thay subscription ACTIVE cho driver. DriverID: {}",
                        booking.getDriver().getId());
                return;
            }

            DriverSubscription subscription = activeSubscriptions.get(0);
            int remainingBefore = subscription.getRemainingSwaps();

            if (remainingBefore > 0) {
                subscription.setRemainingSwaps(remainingBefore - 1);

                if (subscription.getRemainingSwaps() == 0) {
                    subscription.setStatus(DriverSubscription.Status.EXPIRED);
                    logger.info("Subscription het luot swap. SubscriptionID: {}, DriverID: {}",
                            subscription.getId(), booking.getDriver().getId());
                }

                driverSubscriptionRepository.save(subscription);

                logger.info("Da tru luot swap vi khong den. DriverID: {}, RemainingSwaps: {} → {}",
                        booking.getDriver().getId(), remainingBefore, subscription.getRemainingSwaps());
            } else {
                logger.warn("Subscription da het luot swap. SubscriptionID: {}, DriverID: {}",
                        subscription.getId(), booking.getDriver().getId());
            }

        } catch (Exception e) {
            logger.error("Loi khi tru luot swap. BookingID: {}, DriverID: {}",
                    booking.getId(), booking.getDriver().getId(), e);
        }
    }

    /**
     * GỬI EMAIL THÔNG BÁO HỦY TỰ ĐỘNG CHO DRIVER
     * Vì booking bị hủy do hết thời gian reservation (3 tiếng)
     */
    private void sendAutoCancellationEmail(Booking booking) {
        try {
            EmailDetail emailDetail = new EmailDetail();
            emailDetail.setRecipient(booking.getDriver().getEmail());
            emailDetail.setSubject("THÔNG BÁO HỦY BOOKING TỰ ĐỘNG - " + booking.getConfirmationCode());
            emailDetail.setFullName(booking.getDriver().getFullName());

            emailDetail.setBookingId(booking.getId());
            emailDetail.setStationName(booking.getStation().getName());
            emailDetail.setStationLocation(
                    booking.getStation().getLocation() != null ? booking.getStation().getLocation() :
                            (booking.getStation().getDistrict() + ", " + booking.getStation().getCity())
            );
            emailDetail.setStationContact(booking.getStation().getContactInfo() != null ? booking.getStation().getContactInfo() : "Chưa cập nhật");

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy");
            emailDetail.setBookingTime(booking.getBookingTime().format(formatter));

            emailDetail.setVehicleModel(booking.getVehicle().getModel() != null ? booking.getVehicle().getModel() : booking.getVehicle().getPlateNumber());
            emailDetail.setBatteryType(
                    booking.getStation().getBatteryType().getName() +
                            (booking.getStation().getBatteryType().getCapacity() != null ? " - " + booking.getStation().getBatteryType().getCapacity() + "kWh" : "")
            );
            emailDetail.setStatus("CANCELLED");
            emailDetail.setConfirmationCode(booking.getConfirmationCode());

            // Thông báo lý do hủy tự động
            emailDetail.setCancellationPolicy(
                    "Booking của bạn đã bị hủy tự động do hết thời gian giữ chỗ (3 giờ). " +
                            "Một lượt swap đã bị trừ khỏi gói dịch vụ của bạn vì không đến thực hiện swap."
            );

            emailService.sendBookingCancellationEmail(emailDetail);

            logger.info("Da gui email thong bao huy tu dong cho driver. BookingID: {}, Driver: {}",
                    booking.getId(), booking.getDriver().getEmail());

        } catch (Exception e) {
            logger.error("Loi khi gui email thong bao huy tu dong. BookingID: {}", booking.getId(), e);
        }
    }
}