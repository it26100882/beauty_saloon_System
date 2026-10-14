package com.salon.service;

import com.salon.exception.BookingException;
import com.salon.exception.ResourceNotFoundException;
import com.salon.exception.SlotNotAvailableException;
import com.salon.model.*;
import com.salon.payment.Payment;
import com.salon.payment.PaymentFactory;
import com.salon.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** All booking business rules live here (availability check, booking, cancel, pay ...). */
@Service
public class AppointmentService {

    /** Start times are offered every 30 minutes. */
    public static final int SLOT_STEP_MINS = 30;

    private final AppointmentRepository appointmentRepository;
    private final StylistRepository stylistRepository;
    private final SalonServiceRepository serviceRepository;
    private final CustomerRepository customerRepository;
    private final PaymentRecordRepository paymentRecordRepository;

    public AppointmentService(AppointmentRepository appointmentRepository,
                              StylistRepository stylistRepository,
                              SalonServiceRepository serviceRepository,
                              CustomerRepository customerRepository,
                              PaymentRecordRepository paymentRecordRepository) {
        this.appointmentRepository = appointmentRepository;
        this.stylistRepository = stylistRepository;
        this.serviceRepository = serviceRepository;
        this.customerRepository = customerRepository;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    // ---------------------------------------------------------------- queries
    public List<Appointment> getForCustomer(Customer customer) {
        return appointmentRepository.findByCustomerOrderByDateDescStartTimeDesc(customer);
    }

    public List<Appointment> getForStylist(Stylist stylist) {
        return appointmentRepository.findByStylistOrderByDateAscStartTimeAsc(stylist);
    }

    public List<Appointment> getAll() {
        return appointmentRepository.findAllByOrderByDateDescStartTimeDesc();
    }

    public Appointment getAppointment(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found."));
    }

    public Appointment getOwnedAppointment(Long id, Customer customer) {
        Appointment appointment = getAppointment(id);
        checkOwner(appointment, customer);
        return appointment;
    }

    public long countByStatus(AppointmentStatus status) {
        return appointmentRepository.countByStatus(status);
    }

    public double totalRevenue() {
        Double total = paymentRecordRepository.totalRevenue();
        return total == null ? 0.0 : total;
    }

    // ---------------------------------------------------------- availability
    /**
     * Returns the start times a stylist is free for the chosen service on a date.
     * ignoreAppointmentId lets a customer re-book into their own current slot when rescheduling.
     */
    public List<LocalTime> getAvailableSlots(Long stylistId, Long serviceId, LocalDate date,
                                             Long ignoreAppointmentId) {
        List<LocalTime> slots = new ArrayList<>();
        if (date == null || date.isBefore(LocalDate.now())) {
            return slots;
        }
        Stylist stylist = stylistRepository.findById(stylistId)
                .orElseThrow(() -> new ResourceNotFoundException("Stylist not found."));
        SalonService service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found."));

        List<Appointment> booked = new ArrayList<>();
        for (Appointment a : appointmentRepository.findByStylistAndDateAndStatusNot(
                stylist, date, AppointmentStatus.CANCELLED)) {
            if (ignoreAppointmentId == null || !a.getId().equals(ignoreAppointmentId)) {
                booked.add(a);
            }
        }

        LocalTime workEnd = stylist.getWorkEnd();
        boolean today = date.equals(LocalDate.now());
        LocalTime now = LocalTime.now();

        for (LocalTime start = stylist.getWorkStart(); start.isBefore(workEnd);
             start = start.plusMinutes(SLOT_STEP_MINS)) {
            LocalTime end = start.plusMinutes(service.getDurationMins());
            if (end.isAfter(workEnd) || end.isBefore(start)) {
                break; // service no longer fits in the working day
            }
            if (today && start.isBefore(now)) {
                continue;
            }
            boolean clash = false;
            for (Appointment a : booked) {
                if (start.isBefore(a.getEndTime()) && a.getStartTime().isBefore(end)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                slots.add(start);
            }
            if (start.plusMinutes(SLOT_STEP_MINS).isBefore(start)) {
                break; // safety: passed midnight
            }
        }
        return slots;
    }

    // --------------------------------------------------------------- actions
    @Transactional
    public Appointment book(Customer customer, Long stylistId, Long serviceId,
                            LocalDate date, LocalTime time) {
        if (!getAvailableSlots(stylistId, serviceId, date, null).contains(time)) {
            throw new SlotNotAvailableException("Sorry, that time slot is no longer available.");
        }
        Stylist stylist = stylistRepository.findById(stylistId)
                .orElseThrow(() -> new ResourceNotFoundException("Stylist not found."));
        SalonService service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found."));
        return appointmentRepository.save(new Appointment(customer, stylist, service, date, time));
    }

    @Transactional
    public void cancel(Long appointmentId, User actor) {
        Appointment appointment = getAppointment(appointmentId);
        boolean isAdmin = "ADMIN".equals(actor.getRole());
        if (!isAdmin && !appointment.getCustomer().getId().equals(actor.getId())) {
            throw new BookingException("You can only cancel your own appointments.");
        }
        if (!appointment.isActive()) {
            throw new BookingException("Only pending or confirmed appointments can be cancelled.");
        }
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointmentRepository.save(appointment);
    }

    @Transactional
    public void reschedule(Long appointmentId, Customer customer, LocalDate newDate, LocalTime newTime) {
        Appointment appointment = getOwnedAppointment(appointmentId, customer);
        if (!appointment.isActive()) {
            throw new BookingException("Only pending or confirmed appointments can be rescheduled.");
        }
        List<LocalTime> slots = getAvailableSlots(appointment.getStylist().getId(),
                appointment.getService().getId(), newDate, appointment.getId());
        if (!slots.contains(newTime)) {
            throw new SlotNotAvailableException("Sorry, that time slot is not available.");
        }
        appointment.reschedule(newDate, newTime);
        appointmentRepository.save(appointment);
    }

    /** Admin or the appointment's own stylist can confirm. */
    @Transactional
    public void confirm(Long appointmentId, User actor) {
        Appointment appointment = getAppointment(appointmentId);
        checkStaff(appointment, actor);
        if (!appointment.isPending()) {
            throw new BookingException("Only pending appointments can be confirmed.");
        }
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointmentRepository.save(appointment);
    }

    /** Admin or the appointment's own stylist can complete. Customer earns loyalty points. */
    @Transactional
    public void complete(Long appointmentId, User actor) {
        Appointment appointment = getAppointment(appointmentId);
        checkStaff(appointment, actor);
        if (!appointment.isConfirmed()) {
            throw new BookingException("Only confirmed appointments can be marked as completed.");
        }
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointmentRepository.save(appointment);

        Customer customer = appointment.getCustomer();
        customer.addLoyaltyPoints((int) (appointment.getTotalPrice() / 100));  // 1 point per Rs. 100
        customerRepository.save(customer);
    }

    @Transactional
    public void pay(Long appointmentId, Customer customer, String method, String cardNumber) {
        Appointment appointment = getOwnedAppointment(appointmentId, customer);
        if (!appointment.isActive()) {
            throw new BookingException("This appointment cannot be paid.");
        }
        if (appointment.isPaid()) {
            throw new BookingException("This appointment is already paid.");
        }
        // POLYMORPHISM: Payment variable, CashPayment or CardPayment object
        Payment payment = PaymentFactory.create(method, cardNumber);
        if (!payment.pay(appointment.getTotalPrice())) {
            throw new BookingException("Payment failed. Please check the card number (16 digits).");
        }
        appointment.setPaid(true);
        appointmentRepository.save(appointment);
        paymentRecordRepository.save(
                new PaymentRecord(appointment, appointment.getTotalPrice(), payment.getMethodName()));
    }

    // --------------------------------------------------------------- helpers
    private void checkOwner(Appointment appointment, Customer customer) {
        if (!appointment.getCustomer().getId().equals(customer.getId())) {
            throw new BookingException("You can only manage your own appointments.");
        }
    }

    private void checkStaff(Appointment appointment, User actor) {
        boolean isAdmin = "ADMIN".equals(actor.getRole());
        boolean ownStylist = "STYLIST".equals(actor.getRole())
                && appointment.getStylist().getId().equals(actor.getId());
        if (!isAdmin && !ownStylist) {
            throw new BookingException("You are not allowed to change this appointment.");
        }
    }
}
