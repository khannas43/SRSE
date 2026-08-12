package gov.rajasthan.smart.srse.scheme;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchemeRepository extends JpaRepository<Scheme, Long> {

    Optional<Scheme> findByCode(String code);

    List<Scheme> findByActiveTrueOrderByName();
}
