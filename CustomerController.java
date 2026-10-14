package com.salon.controller;

import com.salon.config.AuthInterceptor;
import com.salon.exception.BookingException;
import com.salon.model.Appointment;
import com.salon.model.Customer;
import com.salon.service.AppointmentService;
import com.salon.service.CatalogService;
import com.salon.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final UserService userService;
    private final CatalogService catalogService;
    private final AppointmentService appointmentService;

    public CustomerController(UserService userService,
                              CatalogService catalogService,
                              AppointmentService appointmentService) {
        this.userService = userService;
        this.catalogService = catalogService;
        this.appointmentService = appointmentService;
    }

    private Customer currentCustomer(HttpSession session) {
        Long id = (Long) session.getAttribute(AuthInterceptor.USER_ID);
        return userService.getCustomer(id);
    }

    // ------------------------------------------------------------ dashboard
    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        Customer customer = currentCustomer(session);
        model.addAttribute("customer", customer);
        model.addAttribute("appointments", appointmentService.getForCustomer(customer));
        return "customer/dashboard";
    }

    // -------------------------------------------------------------- booking
    @GetMapping("/book")
    public String bookForm(@RequestParam(required = false) Long serviceId,
                           @RequestParam(required = false) Long stylistId,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           Model model) {
        model.addAttribute("services", catalogService.listServices());
        model.addAttribute("stylists", catalogService.listStylists());
        model.addAttribute("selectedServiceId", serviceId);
        model.addAttribute("selectedStylistId", stylistId);
        model.addAttribute("selectedDate", date);
        model.addAttribute("today", LocalDate.now());

        if (serviceId != null && stylistId != null && date != null) {
            try {
                List<LocalTime> slots = appointmentService.getAvailableSlots(stylistId, serviceId, date, null);
                model.addAttribute("slots", slots);
                model.addAttribute("chosenService", catalogService.getService(serviceId));
            } catch (BookingException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "customer/book";
    }

    @PostMapping("/book")
    public String book(@RequestParam Long serviceId,
                       @RequestParam Long stylistId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam String time,
                       HttpSession session,
                       RedirectAttributes redirectAttributes) {
        try {
            appointmentService.book(currentCustomer(session), stylistId, serviceId, date, LocalTime.parse(time));
            redirectAttributes.addFlashAttribute("success",
                    "Appointment booked! It will be confirmed by the salon shortly.");
            return "redirect:/customer/dashboard";
        } catch (BookingException | DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/customer/book?serviceId=" + serviceId + "&stylistId=" + stylistId + "&date=" + date;
        }
    }

    // --------------------------------------------------------------- cancel
    @PostMapping("/appointments/{id}/cancel")
    public String cancel(@PathVariable Long id, HttpSession session, RedirectAttributes redirectAttributes) {
        try {
            appointmentService.cancel(id, currentCustomer(session));
            redirectAttributes.addFlashAttribute("success", "Appointment cancelled.");
        } catch (BookingException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/dashboard";
    }

    // ----------------------------------------------------------- reschedule
    @GetMapping("/appointments/{id}/reschedule")
    public String rescheduleForm(@PathVariable Long id,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 HttpSession session,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        try {
            Appointment appointment = appointmentService.getOwnedAppointment(id, currentCustomer(session));
            model.addAttribute("appointment", appointment);
            model.addAttribute("selectedDate", date);
            model.addAttribute("today", LocalDate.now());
            if (date != null) {
                model.addAttribute("slots", appointmentService.getAvailableSlots(
                        appointment.getStylist().getId(), appointment.getService().getId(), date, appointment.getId()));
            }
            return "customer/reschedule";
        } catch (BookingException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/customer/dashboard";
        }
    }

    @PostMapping("/appointments/{id}/reschedule")
    public String reschedule(@PathVariable Long id,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam String time,
                             HttpSession session,
                             RedirectAttributes redirectAttributes) {
        try {
            appointmentService.reschedule(id, currentCustomer(session), date, LocalTime.parse(time));
            redirectAttributes.addFlashAttribute("success", "Appointment rescheduled. Waiting for confirmation.");
            return "redirect:/customer/dashboard";
        } catch (BookingException | DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/customer/appointments/" + id + "/reschedule?date=" + date;
        }
    }

    // ------------------------------------------------------------- payment
    @GetMapping("/appointments/{id}/pay")
    public String payForm(@PathVariable Long id, HttpSession session, Model model,
                          RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("appointment", appointmentService.getOwnedAppointment(id, currentCustomer(session)));
            return "customer/pay";
        } catch (BookingException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/customer/dashboard";
        }
    }

    @PostMapping("/appointments/{id}/pay")
    public String pay(@PathVariable Long id,
                      @RequestParam String method,
                      @RequestParam(required = false) String cardNumber,
                      HttpSession session,
                      RedirectAttributes redirectAttributes) {
        try {
            appointmentService.pay(id, currentCustomer(session), method, cardNumber);
            redirectAttributes.addFlashAttribute("success", "Payment successful. Thank you!");
            return "redirect:/customer/dashboard";
        } catch (BookingException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/customer/appointments/" + id + "/pay";
        }
    }
}
