package com.imdemo.im.dto;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.City;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.domain.ShiftSession;
import com.imdemo.im.dto.Dto.*;

import java.util.Comparator;

/** Вызывать только внутри @Transactional (lazy-связи). */
public final class Mappers {
    private Mappers() {}

    public static CityDto city(City c) {
        return new CityDto(c.getId(), c.getName());
    }

    public static OutletDto outlet(Outlet o) {
        return new OutletDto(o.getId(), o.getCity().getId(), o.getCity().getName(),
                o.getName(), o.getAddress(), o.isActive());
    }

    public static UserDto user(AppUser u) {
        return new UserDto(u.getId(), u.getLogin(), u.getFullName(), u.getAccountRole(),
                u.isActive(), u.isMustChangePassword(),
                u.getOutlets().stream().map(Mappers::outlet)
                        .sorted(Comparator.comparing(OutletDto::id)).toList());
    }

    public static ShiftDto shift(ShiftSession s) {
        return new ShiftDto(s.getId(), s.getOutlet().getId(), s.getOutlet().getName(),
                s.getShiftRole(), s.getDayPart(), s.getShiftDate(), s.getStartedAt(), s.getFinishedAt());
    }
}