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
}