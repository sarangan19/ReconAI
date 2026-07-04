from enum import Enum
from pydantic import BaseModel, Field


class DiscrepancyCode(str, Enum):
    DUP_EXTERNAL     = "DUP_EXTERNAL"
    MISSING_EXTERNAL = "MISSING_EXTERNAL"
    MISSING_INTERNAL = "MISSING_INTERNAL"
    AMT_FX_ROUNDING  = "AMT_FX_ROUNDING"
    AMT_FAT_FINGER   = "AMT_FAT_FINGER"
    DATE_TIMING      = "DATE_TIMING"
    REF_CORRUPTION   = "REF_CORRUPTION"
    SPLIT_SETTLEMENT = "SPLIT_SETTLEMENT"


class Action(str, Enum):
    WAIT_SELF_CLEAR          = "WAIT_SELF_CLEAR"
    REVERSE_DUPLICATE        = "REVERSE_DUPLICATE"
    CREATE_MISSING_POSTING   = "CREATE_MISSING_POSTING"
    APPROVE_TOLERANCE        = "APPROVE_TOLERANCE"
    REPROCESS_WITH_CORRECT_REF = "REPROCESS_WITH_CORRECT_REF"
    MANUAL_REVIEW            = "MANUAL_REVIEW"


class Verdict(BaseModel):
    root_cause_code:  DiscrepancyCode
    confidence:       float = Field(ge=0.0, le=1.0)
    explanation:      str   = Field(min_length=10)
    suggested_action: Action
