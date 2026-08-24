"""Enumerations for the Alert Triage Sidecar data models.

These three enums pin down the closed value sets the spec repeatedly refers to:

  * ``TriggerType``   - the four ``Triage_Trigger`` conditions (R2.1-R2.4).
  * ``ThreatCategory`` - the five permitted threat-classification labels
    (R7.4, R9.2, R9.3).
  * ``VerdictValue``  - the four permitted verdict values (R9.1, R9.2).

Each enum subclasses ``str`` so members serialize to their plain string value
and compare equal to that string, which keeps the JSON representation on the
``Integration_Contract`` stable and human-readable.
"""

from __future__ import annotations

from enum import Enum


class TriggerType(str, Enum):
    """The four qualifying high-risk conditions that start a triage run.

    An incoming event carrying none of these is discarded (R2.5). Requirements
    R2.1-R2.4 map one trigger condition to each member.
    """

    BLOCK_RANGE_DIVERGENCE = "BLOCK_RANGE_DIVERGENCE"  # block-level Divergence_Score (R2.1)
    SESSION_HIJACK = "SESSION_HIJACK"                  # SessionAnomalyDetector signal (R2.2)
    MONITORING_GAP = "MONITORING_GAP"                  # MonitoringGapWatchdog gap (R2.3)
    CANARY_TOKEN = "CANARY_TOKEN"                      # Canary_Token hit (R2.4)


class ThreatCategory(str, Enum):
    """The permitted ``Threat_Category`` labels a hypothesis maps to (R7.4).

    Verdict synthesis also constrains its ``threatCategory`` to this set; a
    value outside it is treated as malformed (R9.2, R9.3).
    """

    PROMPT_INJECTION = "prompt-injection"
    SESSION_HIJACK = "session-hijack"
    OFF_INTENT_AGENT = "off-intent-agent"
    BENIGN_ANOMALY = "benign-anomaly"
    FALSE_POSITIVE = "false-positive"


class VerdictValue(str, Enum):
    """The permitted synthesized ``Verdict`` values (R9.1).

    A synthesized value outside this set is malformed and forces the
    fail-open-to-human path: ``uncertain`` with confidence ``0.0`` (R9.4).
    """

    CONFIRMED_THREAT = "confirmed_threat"
    BENIGN = "benign"
    FALSE_POSITIVE = "false_positive"
    UNCERTAIN = "uncertain"


__all__ = ["TriggerType", "ThreatCategory", "VerdictValue"]
