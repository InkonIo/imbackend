package com.imdemo.im.service;

import com.imdemo.im.domain.AuditEventType;
import com.imdemo.im.domain.City;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.dto.Mappers;
import com.imdemo.im.repo.CityRepository;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CityRepository cities;
    private final OutletRepository outlets;
    private final AuditService audit;

    // ---------- города ----------

    @Transactional(readOnly = true)
    public List<CityDto> cities() {
        return cities.findAll(Sort.by("name")).stream().map(Mappers::city).toList();
    }

    @Transactional
    public CityDto createCity(CityRequest r) {
        City c = new City();
        c.setName(r.name().trim());
        City saved = cities.saveAndFlush(c);
        audit.log(AuditEventType.CATALOG_CHANGED, "city", saved.getId(), "Город создан: " + saved.getName());
        return Mappers.city(saved);
    }

    @Transactional
    public CityDto updateCity(Long id, CityRequest r) {
        City c = city(id);
        String old = c.getName();
        c.setName(r.name().trim());
        City saved = cities.saveAndFlush(c);
        audit.log(AuditEventType.CATALOG_CHANGED, "city", saved.getId(), "Город: " + old + " → " + saved.getName());
        return Mappers.city(saved);
    }

    @Transactional
    public void deleteCity(Long id) {
        City c = city(id);
        audit.log(AuditEventType.CATALOG_CHANGED, "city", c.getId(), "Город удалён: " + c.getName());
        cities.delete(c);
        cities.flush();
    }

    // ---------- точки ----------

    @Transactional(readOnly = true)
    public List<OutletDto> outlets() {
        return outlets.findAll(Sort.by("id")).stream().map(Mappers::outlet).toList();
    }

    @Transactional
    public OutletDto createOutlet(OutletRequest r) {
        Outlet o = new Outlet();
        apply(o, r);
        Outlet saved = outlets.saveAndFlush(o);
        audit.log(AuditEventType.CATALOG_CHANGED, "outlet", saved.getId(), "Точка создана: " + saved.getName());
        return Mappers.outlet(saved);
    }

    @Transactional
    public OutletDto updateOutlet(Long id, OutletRequest r) {
        Outlet o = outlet(id);
        boolean wasActive = o.isActive();
        apply(o, r);
        Outlet saved = outlets.saveAndFlush(o);
        String what = wasActive != saved.isActive()
                ? (saved.isActive() ? "включена" : "выключена")
                : "изменена";
        audit.log(AuditEventType.CATALOG_CHANGED, "outlet", saved.getId(), "Точка " + what + ": " + saved.getName());
        return Mappers.outlet(saved);
    }

    @Transactional
    public void deleteOutlet(Long id) {
        Outlet o = outlet(id);
        audit.log(AuditEventType.CATALOG_CHANGED, "outlet", o.getId(), "Точка удалена: " + o.getName());
        outlets.delete(o);
        outlets.flush();
    }

    private void apply(Outlet o, OutletRequest r) {
        o.setCity(city(r.cityId()));
        o.setName(r.name().trim());
        o.setAddress(r.address());
        if (r.active() != null) {
            o.setActive(r.active());
        }
    }

    private City city(Long id) {
        return cities.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Город не найден"));
    }

    private Outlet outlet(Long id) {
        return outlets.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Точка не найдена"));
    }
}