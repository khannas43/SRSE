package gov.rajasthan.smart.srse.scheme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A welfare scheme that saved rulesets ({@code scenario}) can be tagged to.
 * Operational-plane entity (DB2/JPA) — this is not the rule execution target
 * itself, just a tag officers attach saved parameter combinations to.
 */
@Entity
@Table(name = "scheme")
public class Scheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    private String name;

    private String description;

    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Scheme() {
    }

    public Scheme(Long id, String code, String name, String description,
                 boolean active, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
