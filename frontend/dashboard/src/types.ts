export type Operator = {
  email: string;
  shopName: string;
  shopSlug: string;
  submissionUrl: string;
};

export type PrintDocument = {
  id: string;
  customerName: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
  expiresAt: string;
  status: "PENDING_CONVERSION" | "READY" | "CONVERSION_FAILED" | "DELETING" | "DELETED";
};
