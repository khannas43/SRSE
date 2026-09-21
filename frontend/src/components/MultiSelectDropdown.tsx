"use client";

import { useEffect, useRef, useState, type CSSProperties } from "react";

const buttonStyle: CSSProperties = {
  width: "100%",
  textAlign: "left",
  padding: "0.5rem 0.65rem",
  border: "1px solid var(--srse-border-strong)",
  borderRadius: "var(--srse-radius-sm)",
  background: "var(--srse-surface)",
  fontSize: "0.875rem",
  cursor: "pointer",
  display: "flex",
  justifyContent: "space-between",
  alignItems: "center",
  gap: "0.5rem",
  transition: "border-color 0.15s ease, box-shadow 0.15s ease",
};

const panelStyle: CSSProperties = {
  position: "absolute",
  top: "calc(100% + 4px)",
  left: 0,
  zIndex: 20,
  minWidth: 220,
  maxHeight: 260,
  overflowY: "auto",
  border: "1px solid var(--srse-border)",
  borderRadius: "var(--srse-radius-sm)",
  background: "var(--srse-surface)",
  boxShadow: "var(--srse-shadow-md)",
  padding: "0.4rem",
};

const optionLabelStyle: CSSProperties = {
  display: "flex",
  gap: "0.4rem",
  alignItems: "center",
  fontSize: "0.85rem",
  padding: "0.35rem 0.4rem",
  borderRadius: "var(--srse-radius-sm)",
};

export type DropdownOption = string | { value: string; label: string };

function normalize(opt: DropdownOption): { value: string; label: string } {
  return typeof opt === "string" ? { value: opt, label: opt } : opt;
}

/**
 * Dropdown supporting select-one, select-multiple, or an explicit "All"/clear
 * option (empty selection). Options may be plain strings (value === label,
 * e.g. district names) or {value, label} pairs (e.g. scheme id -> name).
 * Closed state shows a one-line summary; open state is a checkbox list,
 * closes on outside click.
 */
export function MultiSelectDropdown({
  options,
  selected,
  onChange,
  allLabel = "All",
  width = 220,
  disabled = false,
}: Readonly<{
  options: DropdownOption[];
  selected: string[];
  onChange: (next: string[]) => void;
  allLabel?: string;
  width?: number | string;
  disabled?: boolean;
}>) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const normalized = options.map(normalize);

  useEffect(() => {
    if (!open) return;
    function onClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, [open]);

  const isAll = selected.length === 0;
  const selectedLabel = (value: string) => normalized.find((o) => o.value === value)?.label ?? value;
  let summary: string;
  if (isAll) {
    summary = allLabel;
  } else if (selected.length === 1) {
    summary = selectedLabel(selected[0]);
  } else {
    summary = `${selected.length} selected`;
  }

  return (
    <div ref={containerRef} style={{ position: "relative", display: "inline-block", width }}>
      <button
        type="button"
        disabled={disabled}
        style={{
          ...buttonStyle,
          borderColor: open ? "var(--srse-primary)" : "var(--srse-border-strong)",
          boxShadow: open ? "0 0 0 3px var(--srse-primary-bg)" : "none",
          opacity: disabled ? 0.6 : 1,
          cursor: disabled ? "not-allowed" : "pointer",
        }}
        onClick={() => !disabled && setOpen((o) => !o)}
      >
        <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{summary}</span>
        <span style={{ fontSize: "0.7rem", color: "var(--srse-text-faint)", flexShrink: 0 }}>{open ? "▲" : "▼"}</span>
      </button>

      {open && (
        <div style={{ ...panelStyle, width }}>
          <label
            style={{
              ...optionLabelStyle,
              fontWeight: 600,
              borderBottom: "1px solid var(--srse-border)",
              marginBottom: "0.3rem",
              paddingBottom: "0.5rem",
            }}
          >
            <input type="checkbox" checked={isAll} onChange={() => onChange([])} />
            {" "}
            {allLabel}
          </label>
          {normalized.map((opt) => (
            <label key={opt.value} style={optionLabelStyle}>
              <input
                type="checkbox"
                checked={selected.includes(opt.value)}
                onChange={() =>
                  onChange(
                    selected.includes(opt.value)
                      ? selected.filter((v) => v !== opt.value)
                      : [...selected, opt.value],
                  )
                }
              />
              {" "}
              {opt.label}
            </label>
          ))}
        </div>
      )}
    </div>
  );
}

const filterInputStyle: CSSProperties = {
  width: "100%",
  boxSizing: "border-box",
  padding: "0.35rem 0.45rem",
  marginBottom: "0.35rem",
  border: "1px solid var(--srse-border-strong)",
  borderRadius: "var(--srse-radius-sm)",
  fontSize: "0.85rem",
};

/**
 * Searchable single-choice dropdown. Same closed-summary / panel / outside-click
 * behaviour as MultiSelectDropdown, with a filter field at the top of the panel.
 */
export function SingleSelectDropdown({
  options,
  selected,
  onChange,
  placeholder = "— select —",
  width = 220,
  disabled = false,
  id,
  ariaLabel,
}: Readonly<{
  options: DropdownOption[];
  selected: string;
  onChange: (value: string) => void;
  placeholder?: string;
  width?: number | string;
  disabled?: boolean;
  id?: string;
  ariaLabel?: string;
}>) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const containerRef = useRef<HTMLDivElement>(null);
  const filterRef = useRef<HTMLInputElement>(null);
  const normalized = options.map(normalize);

  function closePanel() {
    setOpen(false);
    setFilter("");
  }

  useEffect(() => {
    if (!open) return;
    function onClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        closePanel();
      }
    }
    document.addEventListener("mousedown", onClickOutside);
    return () => document.removeEventListener("mousedown", onClickOutside);
  }, [open]);

  useEffect(() => {
    if (open) {
      filterRef.current?.focus();
    }
  }, [open]);

  const needle = filter.trim().toLowerCase();
  const filtered =
    needle === ""
      ? normalized
      : normalized.filter(
          (o) => o.label.toLowerCase().includes(needle) || o.value.toLowerCase().includes(needle),
        );

  const summary = selected
    ? (normalized.find((o) => o.value === selected)?.label ?? selected)
    : placeholder;

  function pick(value: string) {
    onChange(value);
    closePanel();
  }

  return (
    <div ref={containerRef} style={{ position: "relative", display: "inline-block", width }}>
      <button
        id={id}
        type="button"
        disabled={disabled}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        style={{
          ...buttonStyle,
          borderColor: open ? "var(--srse-primary)" : "var(--srse-border-strong)",
          boxShadow: open ? "0 0 0 3px var(--srse-primary-bg)" : "none",
          opacity: disabled ? 0.6 : 1,
          cursor: disabled ? "not-allowed" : "pointer",
        }}
        onClick={() => {
          if (disabled) return;
          if (open) closePanel();
          else setOpen(true);
        }}
      >
        <span
          style={{
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
            color: selected ? undefined : "var(--srse-text-faint)",
          }}
        >
          {summary}
        </span>
        <span style={{ fontSize: "0.7rem", color: "var(--srse-text-faint)", flexShrink: 0 }}>{open ? "▲" : "▼"}</span>
      </button>

      {open && (
        <div style={{ ...panelStyle, width }} role="listbox">
          <input
            ref={filterRef}
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Filter…"
            className="srse-input"
            style={filterInputStyle}
            aria-label={ariaLabel ? `${ariaLabel} filter` : "Filter options"}
            onKeyDown={(e) => e.stopPropagation()}
          />
          {filtered.length === 0 ? (
            <p className="srse-text-muted" style={{ margin: "0.25rem 0.35rem", fontSize: "0.8rem" }}>
              No matches
            </p>
          ) : (
            filtered.map((opt) => (
              <button
                key={opt.value}
                type="button"
                role="option"
                aria-selected={selected === opt.value}
                style={{
                  ...optionLabelStyle,
                  width: "100%",
                  border: "none",
                  background: selected === opt.value ? "var(--srse-primary-bg)" : "transparent",
                  cursor: "pointer",
                  textAlign: "left",
                }}
                onClick={() => pick(opt.value)}
              >
                {opt.label}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}
