package com.salon.repository;

import com.salon.model.Appointment;
import com.salon.model.AppointmentStatus;
import com.salon.model.Customer;
import com.salon.model.Stylist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByCustomerOrderByDateDescStartTimeDesc(Customer customer);

    List<Appointment> findByStylistOrderByDateAscStartTimeAsc(Stylist stylist);

    List<Appointment> findAllByOrderByDateDescStartTimeDesc();

    List<Appointment> findByStylistAndDateAndStatusNot(Stylist stylist, LocalDate date, AppointmentStatus status);

    long countByStatus(AppointmentStatus status);
}
