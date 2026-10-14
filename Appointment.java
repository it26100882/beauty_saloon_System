package com.salon.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(optional = false)
    @JoinColumn(name = "stylist_id")
    private Stylist stylist;

    @ManyToOne(optional = false)
    @JoinColumn(name = "service_id")
    private SalonService service;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status = AppointmentStatus.PENDING;

    private double totalPrice;

    private boolean paid;

    protected Appointment() {
    }

    public Appointment(Customer customer, Stylist stylist, SalonService service,
                       LocalDate date, LocalTime startTime) {
        this.customer = customer;
        this.stylist = stylist;
        this.service = service;
        this.date = date;
        this.startTime = startTime;
        this.endTime = startTime.plusMinutes(service.getDurationMins());
        this.totalPrice = service.calculatePrice();   // polymorphic call
        this.status = AppointmentStatus.PENDING;
    }

    public void reschedule(LocalDate newDate, LocalTime newStart) {
        this.date = newDate;
        this.startTime = newStart;
        this.endTime = newStart.plusMinutes(service.getDurationMins());
        this.status = AppointmentStatus.PENDING;
    }

    // ----- helper methods used by the HTML pages -----
    public boolean isActive() {
        return status == AppointmentStatus.PENDING || status == AppointmentStatus.CONFIRMED;
    }

    public boolean isPending() {
        return status == AppointmentStatus.PENDING;
    }

    public boolean isConfirmed() {
        return status == AppointmentStatus.CONFIRMED;
    }

    public Long getId() { return id; }

    public Customer getCustomer() { return customer; }
    public Stylist getStylist() { return stylist; }
    public SalonService getService() { return service; }

    public LocalDate getDate() { return date; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }

    public AppointmentStatus getStatus() { return status; }
    public void setStatus(AppointmentStatus status) { this.status = status; }

    public double getTotalPrice() { return totalPrice; }

    public boolean isPaid() { return paid; }
    public void setPaid(boolean paid) { this.paid = paid; }
}
