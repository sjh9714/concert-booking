import { z } from "zod";

export const userSchema = z.object({
  userId: z.number(),
  email: z.email(),
  nickname: z.string(),
});

export const authResponseSchema = userSchema.extend({
  token: z.string().min(1),
  expiresAt: z.string().optional(),
});

export const concertSchema = z.object({
  id: z.number(),
  title: z.string(),
  description: z.string().nullable().optional().transform((value) => value ?? ""),
  venue: z.string(),
  artist: z.string(),
});

export const concertListSchema = z.array(concertSchema);

export const scheduleSchema = z.object({
  id: z.number(),
  concertId: z.number(),
  scheduleDate: z.string(),
  startTime: z.string(),
  totalSeats: z.number(),
  availableSeats: z.number(),
  timeZone: z.string().optional().default("Asia/Seoul"),
});

export const scheduleListSchema = z.array(scheduleSchema);

export const seatStatusSchema = z.enum(["AVAILABLE", "HELD", "RESERVED"]);

export const seatSchema = z.object({
  id: z.number(),
  section: z.string(),
  rowNumber: z.number(),
  seatNumber: z.number(),
  price: z.number(),
  status: seatStatusSchema,
});

export const seatListSchema = z.array(seatSchema);

export const queueStatusSchema = z.enum([
  "WAITING",
  "READY",
  "ADMITTED",
  "NOT_JOINED",
  "EXPIRED",
]);

export const queuePositionSchema = z
  .object({
    status: queueStatusSchema.optional(),
    position: z.number().nullable(),
    totalWaiting: z.number(),
    serverTime: z.string().optional(),
    estimatedWaitTime: z.string().optional(),
  })
  .transform((value) => ({
    ...value,
    status:
      value.status ??
      (value.position === 0 || value.position === null
        ? ("NOT_JOINED" as const)
        : value.position <= 100
          ? ("READY" as const)
          : ("WAITING" as const)),
  }));

export const queueTokenSchema = z.object({
  token: z.string().min(1),
  scheduleId: z.number(),
  expiresAt: z.string(),
});

export const reservationStatusSchema = z.enum([
  "PENDING",
  "CONFIRMED",
  "CANCELLED",
  "EXPIRED",
]);

const reservationBaseSchema = z.object({
  id: z.number(),
  reservationKey: z.string(),
  status: reservationStatusSchema,
  totalAmount: z.number(),
  expiresAt: z.string().nullable(),
  createdAt: z.string(),
  concertTitle: z.string().optional(),
  artist: z.string().optional(),
  venue: z.string().optional(),
  scheduleDate: z.string().optional(),
  startTime: z.string().optional(),
  timeZone: z.string().optional(),
  seatLabels: z.array(z.string()).optional(),
  paymentStatus: z.string().nullable().optional(),
});

export const reservationSummarySchema = reservationBaseSchema;
export const reservationListSchema = z.array(reservationSummarySchema);

export const reservationDetailSchema = reservationBaseSchema.extend({
  concertTitle: z.string(),
  venue: z.string(),
  seats: seatListSchema,
});

export const paymentSchema = z.object({
  id: z.number(),
  paymentKey: z.string(),
  reservationId: z.number(),
  amount: z.number(),
  status: z.string(),
  createdAt: z.string(),
});

export const errorResponseSchema = z.object({
  code: z.string().optional(),
  message: z.string().optional(),
  timestamp: z.string().optional(),
});

export type User = z.infer<typeof userSchema>;
export type AuthResponse = z.infer<typeof authResponseSchema>;
export type Concert = z.infer<typeof concertSchema>;
export type ConcertSchedule = z.infer<typeof scheduleSchema>;
export type Seat = z.infer<typeof seatSchema>;
export type QueuePosition = z.infer<typeof queuePositionSchema>;
export type QueueToken = z.infer<typeof queueTokenSchema>;
export type ReservationSummary = z.infer<typeof reservationSummarySchema>;
export type ReservationDetail = z.infer<typeof reservationDetailSchema>;
export type Payment = z.infer<typeof paymentSchema>;
