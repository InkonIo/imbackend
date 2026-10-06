package com.imdemo.im.schedule.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "outlet_staffing")
@Getter
@Setter
@NoArgsConstructor
public class OutletStaffing {
    @Id
    @Column(name = "outlet_id")
    private Long outletId;

    @Column(nullable = false, length = 100)
    private String morning = "INSIDE,SERVICE_MANAGER";

    @Column(nullable = false, length = 100)
    private String evening = "INSIDE,SERVICE_MANAGER";

    /** Промежи: 'SERVICE_MANAGER@12:00-21:00;PRODUCTION_MANAGER@11:00-19:00'. */
    @Column(nullable = false, length = 300)
    private String middle = "";
}